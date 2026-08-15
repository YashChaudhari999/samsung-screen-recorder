package com.example.ssr;

import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

public class GLRenderWrapper implements SurfaceTexture.OnFrameAvailableListener {

    private EGLDisplay mEGLDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext mEGLContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface mEGLSurface = EGL14.EGL_NO_SURFACE;
    
    private Surface mEncoderSurface;
    private SurfaceTexture mInputSurfaceTexture;
    private Surface mInputSurface;
    private int mTextureId;
    private int mProgram;
    
    private final float[] mSTMatrix = new float[16];
    
    private int mWidth;
    private int mHeight;
    private Rect mCropRect; // In screen coordinates
    private int mScreenWidth;
    private int mScreenHeight;
    
    private int muSTMatrixHandle;
    private int muMVPMatrixHandle;
    private int maPositionHandle;
    private int maTextureHandle;

    private static final String VERTEX_SHADER =
            "uniform mat4 uMVPMatrix;\n" +
            "uniform mat4 uSTMatrix;\n" +
            "attribute vec4 aPosition;\n" +
            "attribute vec4 aTextureCoord;\n" +
            "varying vec2 vTextureCoord;\n" +
            "void main() {\n" +
            "  gl_Position = uMVPMatrix * aPosition;\n" +
            "  vTextureCoord = (uSTMatrix * aTextureCoord).xy;\n" +
            "}\n";

    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 vTextureCoord;\n" +
            "uniform samplerExternalOES sTexture;\n" +
            "void main() {\n" +
            "  gl_FragColor = texture2D(sTexture, vTextureCoord);\n" +
            "}\n";

    private final float[] mTriangleVerticesData = {
            // X, Y, Z, U, V
            -1.0f, -1.0f, 0, 0.f, 0.f,
             1.0f, -1.0f, 0, 1.f, 0.f,
            -1.0f,  1.0f, 0, 0.f, 1.f,
             1.0f,  1.0f, 0, 1.f, 1.f,
    };
    private FloatBuffer mTriangleVertices;
    private final Object mFrameSyncObject = new Object();
    private boolean mFrameAvailable;
    
    private Thread mRenderThread;
    private volatile boolean mRunning;

    public GLRenderWrapper(Surface encoderSurface, int encWidth, int encHeight, 
                           int screenWidth, int screenHeight, Rect cropRect) {
        mEncoderSurface = encoderSurface;
        mWidth = encWidth;
        mHeight = encHeight;
        mScreenWidth = screenWidth;
        mScreenHeight = screenHeight;
        mCropRect = cropRect;
        
        mTriangleVertices = ByteBuffer.allocateDirect(
                mTriangleVerticesData.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        mTriangleVertices.put(mTriangleVerticesData).position(0);
    }
    
    public void start() {
        mRunning = true;
        mRenderThread = new Thread(this::renderLoop, "GLRenderThread");
        mRenderThread.start();
        
        // Wait until input surface is ready
        while (mInputSurface == null && mRunning) {
            try { Thread.sleep(10); } catch (InterruptedException ignored) {}
        }
    }
    
    public Surface getInputSurface() {
        return mInputSurface;
    }
    
    private void renderLoop() {
        eglSetup();
        makeCurrent();
        setupGL();
        
        while (mRunning) {
            synchronized (mFrameSyncObject) {
                while (!mFrameAvailable && mRunning) {
                    try {
                        mFrameSyncObject.wait(100);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
                if (!mRunning) break;
                mFrameAvailable = false;
            }
            
            mInputSurfaceTexture.updateTexImage();
            mInputSurfaceTexture.getTransformMatrix(mSTMatrix);
            
            drawFrame();
            
            EGLExt.eglPresentationTimeANDROID(mEGLDisplay, mEGLSurface, mInputSurfaceTexture.getTimestamp());
            EGL14.eglSwapBuffers(mEGLDisplay, mEGLSurface);
        }
        
        releaseGL();
    }

    private void drawFrame() {
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        
        GLES20.glUseProgram(mProgram);
        
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mTextureId);
        
        mTriangleVertices.position(0);
        GLES20.glVertexAttribPointer(maPositionHandle, 3, GLES20.GL_FLOAT, false,
                5 * 4, mTriangleVertices);
        GLES20.glEnableVertexAttribArray(maPositionHandle);
        mTriangleVertices.position(3);
        GLES20.glVertexAttribPointer(maTextureHandle, 2, GLES20.GL_FLOAT, false,
                5 * 4, mTriangleVertices);
        GLES20.glEnableVertexAttribArray(maTextureHandle);
        
        float[] mvpMatrix = new float[16];
        Matrix.setIdentityM(mvpMatrix, 0);
        
        if (mCropRect != null) {
            // Apply crop. Virtual display writes to texture using SurfaceTexture's matrix.
            // By applying our transform AFTER mSTMatrix, we can operate in standard
            // top-down normalized screen coordinates (0..1).
            float cropLeft = (float) mCropRect.left / mScreenWidth;
            float cropRight = (float) mCropRect.right / mScreenWidth;
            float cropTop = (float) mCropRect.top / mScreenHeight;
            float cropBottom = (float) mCropRect.bottom / mScreenHeight;
            
            float widthScale = cropRight - cropLeft;
            float heightScale = cropBottom - cropTop;
            
            // Adjust STMatrix to sample the cropped region
            float[] transform = new float[16];
            Matrix.setIdentityM(transform, 0);
            Matrix.translateM(transform, 0, cropLeft, cropTop, 0);
            Matrix.scaleM(transform, 0, widthScale, heightScale, 1.0f);
            
            float[] finalST = new float[16];
            // Multiply: finalST = transform * mSTMatrix
            // This applies mSTMatrix first, then our custom crop transform
            Matrix.multiplyMM(finalST, 0, transform, 0, mSTMatrix, 0);
            System.arraycopy(finalST, 0, mSTMatrix, 0, 16);
        }
        
        GLES20.glUniformMatrix4fv(muMVPMatrixHandle, 1, false, mvpMatrix, 0);
        GLES20.glUniformMatrix4fv(muSTMatrixHandle, 1, false, mSTMatrix, 0);
        
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }
    
    @Override
    public void onFrameAvailable(SurfaceTexture surfaceTexture) {
        synchronized (mFrameSyncObject) {
            mFrameAvailable = true;
            mFrameSyncObject.notifyAll();
        }
    }
    
    private void eglSetup() {
        mEGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] version = new int[2];
        EGL14.eglInitialize(mEGLDisplay, version, 0, version, 1);
        
        int[] attribList = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGLExt.EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        EGL14.eglChooseConfig(mEGLDisplay, attribList, 0, configs, 0, configs.length, numConfigs, 0);
        
        int[] contextAttribs = {
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
        };
        mEGLContext = EGL14.eglCreateContext(mEGLDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0);
        
        int[] surfaceAttribs = { EGL14.EGL_NONE };
        mEGLSurface = EGL14.eglCreateWindowSurface(mEGLDisplay, configs[0], mEncoderSurface, surfaceAttribs, 0);
    }
    
    private void makeCurrent() {
        EGL14.eglMakeCurrent(mEGLDisplay, mEGLSurface, mEGLSurface, mEGLContext);
    }
    
    private void setupGL() {
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        mTextureId = textures[0];
        
        mInputSurfaceTexture = new SurfaceTexture(mTextureId);
        mInputSurfaceTexture.setDefaultBufferSize(mScreenWidth, mScreenHeight);
        mInputSurfaceTexture.setOnFrameAvailableListener(this);
        mInputSurface = new Surface(mInputSurfaceTexture);
        
        mProgram = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        maPositionHandle = GLES20.glGetAttribLocation(mProgram, "aPosition");
        maTextureHandle = GLES20.glGetAttribLocation(mProgram, "aTextureCoord");
        muMVPMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uMVPMatrix");
        muSTMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uSTMatrix");
    }
    
    private int createProgram(String vertexSource, String fragmentSource) {
        int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        int pixelShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertexShader);
        GLES20.glAttachShader(program, pixelShader);
        GLES20.glLinkProgram(program);
        return program;
    }
    
    private int loadShader(int shaderType, String source) {
        int shader = GLES20.glCreateShader(shaderType);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        return shader;
    }

    public void stop() {
        mRunning = false;
        synchronized (mFrameSyncObject) {
            mFrameSyncObject.notifyAll();
        }
        if (mRenderThread != null) {
            try { mRenderThread.join(); } catch (InterruptedException ignored) {}
        }
    }
    
    private void releaseGL() {
        if (mInputSurface != null) {
            mInputSurface.release();
            mInputSurface = null;
        }
        if (mInputSurfaceTexture != null) {
            mInputSurfaceTexture.release();
            mInputSurfaceTexture = null;
        }
        if (mEGLDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(mEGLDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            EGL14.eglDestroySurface(mEGLDisplay, mEGLSurface);
            EGL14.eglDestroyContext(mEGLDisplay, mEGLContext);
            EGL14.eglReleaseThread();
            EGL14.eglTerminate(mEGLDisplay);
        }
        mEGLDisplay = EGL14.EGL_NO_DISPLAY;
        mEGLContext = EGL14.EGL_NO_CONTEXT;
        mEGLSurface = EGL14.EGL_NO_SURFACE;
    }
}

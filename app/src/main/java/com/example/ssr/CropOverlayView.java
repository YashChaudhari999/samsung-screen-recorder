package com.example.ssr;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

public class CropOverlayView extends View {
    private Paint mDimPaint;
    private Paint mClearPaint;
    private Paint mBorderPaint;
    private Paint mHandlePaint;
    private Rect mCropRect;
    
    private static final int TOUCH_TOLERANCE = 80;
    private static final int HANDLE_RADIUS = 15; // px
    private static final int MIN_SIZE = 200; // px
    
    private int mDraggingMode = 0; // 0=none, 1=move, 2=resize_top, 3=resize_bottom, 4=resize_left, 5=resize_right, 6=resize_top_left, 7=resize_top_right, 8=resize_bottom_left, 9=resize_bottom_right
    private int mStartX, mStartY;

    public interface OnCropRectChangeListener {
        void onCropRectChanged(Rect rect);
    }
    private OnCropRectChangeListener mListener;

    public CropOverlayView(Context context) {
        super(context);
        init();
    }

    public CropOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public void setOnCropRectChangeListener(OnCropRectChangeListener listener) {
        mListener = listener;
    }

    private void init() {
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        
        mDimPaint = new Paint();
        mDimPaint.setColor(Color.parseColor("#99000000")); // Subtle dimming
        
        mClearPaint = new Paint();
        mClearPaint.setColor(Color.TRANSPARENT);
        mClearPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        
        mBorderPaint = new Paint();
        mBorderPaint.setColor(Color.WHITE);
        mBorderPaint.setStyle(Paint.Style.STROKE);
        mBorderPaint.setStrokeWidth(3);
        
        mHandlePaint = new Paint();
        mHandlePaint.setColor(Color.WHITE);
        mHandlePaint.setStyle(Paint.Style.FILL);
        mHandlePaint.setAntiAlias(true);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (mCropRect == null && w > 0 && h > 0) {
            // Default center rect
            int cw = (int) (w * 0.6f);
            int ch = (int) (h * 0.4f);
            int cx = w / 2;
            int cy = h / 2;
            mCropRect = new Rect(cx - cw / 2, cy - ch / 2, cx + cw / 2, cy + ch / 2);
            notifyListener();
        }
    }

    public Rect getCropRect() {
        return mCropRect;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (mCropRect == null) return;
        
        // Draw dim background
        canvas.drawRect(0, 0, getWidth(), getHeight(), mDimPaint);
        
        // Clear the crop area
        canvas.drawRect(mCropRect, mClearPaint);
        
        // Draw thin white border
        canvas.drawRect(mCropRect, mBorderPaint);
        
        // Draw top center handle
        int hx = mCropRect.centerX();
        int hy = mCropRect.top;
        canvas.drawCircle(hx, hy, HANDLE_RADIUS, mHandlePaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mCropRect == null) return false;
        
        int x = (int) event.getX();
        int y = (int) event.getY();
        
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                mDraggingMode = getDragMode(x, y);
                mStartX = x;
                mStartY = y;
                return mDraggingMode != 0;
                
            case MotionEvent.ACTION_MOVE:
                if (mDraggingMode != 0) {
                    int dx = x - mStartX;
                    int dy = y - mStartY;
                    
                    Rect newRect = new Rect(mCropRect);
                    
                    if (mDraggingMode == 1) { // Move
                        newRect.offset(dx, dy);
                    } else { // Resize
                        if (mDraggingMode == 2 || mDraggingMode == 6 || mDraggingMode == 7) newRect.top += dy; // Top
                        if (mDraggingMode == 3 || mDraggingMode == 8 || mDraggingMode == 9) newRect.bottom += dy; // Bottom
                        if (mDraggingMode == 4 || mDraggingMode == 6 || mDraggingMode == 8) newRect.left += dx; // Left
                        if (mDraggingMode == 5 || mDraggingMode == 7 || mDraggingMode == 9) newRect.right += dx; // Right
                    }
                    
                    // Clamp bounds
                    if (newRect.width() >= MIN_SIZE && newRect.height() >= MIN_SIZE) {
                        if (newRect.left >= 0 && newRect.top >= 0 && newRect.right <= getWidth() && newRect.bottom <= getHeight()) {
                            mCropRect.set(newRect);
                            mStartX = x;
                            mStartY = y;
                            invalidate();
                            notifyListener();
                        } else if (mDraggingMode == 1) {
                            // If moving, just clamp it instead of failing
                            if (newRect.left < 0) newRect.offset(-newRect.left, 0);
                            if (newRect.top < 0) newRect.offset(0, -newRect.top);
                            if (newRect.right > getWidth()) newRect.offset(getWidth() - newRect.right, 0);
                            if (newRect.bottom > getHeight()) newRect.offset(0, getHeight() - newRect.bottom);
                            mCropRect.set(newRect);
                            mStartX = x;
                            mStartY = y;
                            invalidate();
                            notifyListener();
                        }
                    }
                }
                break;
                
            case MotionEvent.ACTION_UP:
                mDraggingMode = 0;
                break;
        }
        return true;
    }
    
    private void notifyListener() {
        if (mListener != null && mCropRect != null) {
            mListener.onCropRectChanged(mCropRect);
        }
    }
    
    private int getDragMode(int x, int y) {
        // Top center handle
        if (Math.abs(x - mCropRect.centerX()) < TOUCH_TOLERANCE && Math.abs(y - mCropRect.top) < TOUCH_TOLERANCE) {
            return 2; // top
        }
        
        // Corners (invisible handles)
        boolean left = Math.abs(x - mCropRect.left) < TOUCH_TOLERANCE;
        boolean right = Math.abs(x - mCropRect.right) < TOUCH_TOLERANCE;
        boolean top = Math.abs(y - mCropRect.top) < TOUCH_TOLERANCE;
        boolean bottom = Math.abs(y - mCropRect.bottom) < TOUCH_TOLERANCE;
        
        if (left && top) return 6;
        if (right && top) return 7;
        if (left && bottom) return 8;
        if (right && bottom) return 9;
        
        // Edges (invisible)
        if (left && y > mCropRect.top && y < mCropRect.bottom) return 4;
        if (right && y > mCropRect.top && y < mCropRect.bottom) return 5;
        if (top && x > mCropRect.left && x < mCropRect.right) return 2;
        if (bottom && x > mCropRect.left && x < mCropRect.right) return 3;
        
        // Inside
        if (mCropRect.contains(x, y)) {
            return 1; // move
        }
        
        return 0; // nothing
    }
}

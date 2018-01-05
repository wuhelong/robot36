
/*
Copyright 2014 Ahmet Inan <xdsopl@googlemail.com>

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
 */

package xdsopl.robot36;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.support.annotation.Nullable;
import android.util.AttributeSet;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

public class ImageView extends SurfaceView implements SurfaceHolder.Callback {
    private Drawable background;
    private int canvasWidth = -1, canvasHeight = -1;
    private boolean cantTouchThis = true;
    private int imageWidth = -1;
    private int imageHeight = -1;
    public boolean intScale = false;
    private final SurfaceHolder holder;
    public Bitmap bitmap = null;
    private final Paint paint;
    private boolean mAdjustViewBounds = false;
    private int mMaxWidth = Integer.MAX_VALUE;
    private int mMaxHeight = Integer.MAX_VALUE;


    public ImageView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ImageView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }
    public ImageView(Context context, @Nullable AttributeSet attrs, int defStyleAttr,
                     int defStyleRes) {
        super(context, attrs, defStyleAttr);
        holder = getHolder();
        holder.addCallback(this);
        paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        final TypedArray a = context.obtainStyledAttributes(attrs, new int[]{
                android.R.attr.adjustViewBounds,
                android.R.attr.maxWidth,
                android.R.attr.maxHeight
        }, defStyleAttr, defStyleRes);
        setAdjustViewBounds(a.getBoolean(0, false));
        setMaxWidth(a.getDimensionPixelSize(1, Integer.MAX_VALUE));
        setMaxHeight(a.getDimensionPixelSize(2, Integer.MAX_VALUE));
        a.recycle();
    }

    @Override
    public void setBackground(Drawable background) {
        if (holder == null) {
            this.background = background;
        } else {
            synchronized (holder) {
                this.background = background;
            }
        }
    }

    @Override
    public Drawable getBackground() {
        return background;
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (background != null) {
            if (holder == null) {
                background.setBounds(left, top, right, bottom);
            } else {
                synchronized (holder) {
                    background.setBounds(left, top, right, bottom);
                }
            }
        }
    }

    public void setImageResolution(int width, int height) {
        synchronized (holder) {
            if (width != imageWidth || height != imageHeight) {
                imageWidth = width;
                imageHeight = height;
                if (bitmap != null)
                    bitmap.recycle();
                bitmap = Bitmap.createBitmap(imageWidth, imageHeight, Bitmap.Config.ARGB_8888);
                post(new Runnable() {
                    @Override
                    public void run() {
                        requestLayout();
                    }
                });
            }
        }
    }

    public void setPixels(int pixels[]) {
        synchronized (holder) {
            bitmap.setPixels(pixels, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
        }
    }

    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        synchronized (this.holder) {
            canvasWidth = width;
            canvasHeight = height;
        }
        drawCanvas();
    }

    public void surfaceCreated(SurfaceHolder holder) {
        synchronized (this.holder) {
            cantTouchThis = false;
        }
    }

    public void surfaceDestroyed(SurfaceHolder holder) {
        synchronized (this.holder) {
            cantTouchThis = true;
        }
    }

    void drawCanvas() {
        synchronized (holder) {
            if (cantTouchThis || bitmap == null)
                return;
            Canvas canvas = null;
            try {
                canvas = holder.lockCanvas(null);
                if (canvas != null)
                    drawBitmap(canvas);
            } finally {
                if (canvas != null)
                    holder.unlockCanvasAndPost(canvas);
            }
        }
    }

    void drawBitmap(Canvas canvas) {
        float sx, sy;
        if (imageWidth * canvasHeight < canvasWidth * bitmap.getHeight()) {
            sy = (float) canvasHeight / bitmap.getHeight();
            sx = sy * imageWidth / bitmap.getWidth();
        } else {
            sx = (float) canvasWidth / bitmap.getWidth();
            sy = (float) canvasWidth / imageWidth;
        }
        if (intScale) {
            sx = (float) Math.max(1, Math.floor(sx));
            sy = (float) Math.max(1, Math.floor(sy));
        }
        float px = (canvasWidth - sx * bitmap.getWidth()) / 2.0f;
        float py = (canvasHeight - sy * bitmap.getHeight()) / 2.0f;
        if (background != null)
            background.draw(canvas);
        canvas.save();
        canvas.scale(sx, sy, px, py);
        canvas.drawBitmap(bitmap, px, py, paint);
        canvas.restore();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w;
        int h;

        // Desired aspect ratio of the view's contents (not including padding)
        float desiredAspect = 0.0f;

        // We are allowed to change the view's width
        boolean resizeWidth = false;

        // We are allowed to change the view's height
        boolean resizeHeight = false;

        final int widthSpecMode = MeasureSpec.getMode(widthMeasureSpec);
        final int heightSpecMode = MeasureSpec.getMode(heightMeasureSpec);

        w = imageWidth;
        h = imageHeight;
        if (w <= 0) w = 1;
        if (h <= 0) h = 1;

        // We are supposed to adjust view bounds to match the aspect
        // ratio of our drawable. See if that is possible.
        if (mAdjustViewBounds) {
            resizeWidth = widthSpecMode != MeasureSpec.EXACTLY;
            resizeHeight = heightSpecMode != MeasureSpec.EXACTLY;

            desiredAspect = (float) w / (float) h;
        }

        final int pleft = getPaddingLeft();
        final int pright = getPaddingRight();
        final int ptop = getPaddingTop();
        final int pbottom = getPaddingBottom();

        int widthSize;
        int heightSize;

        if (resizeWidth || resizeHeight) {
            /* If we get here, it means we want to resize to match the
                drawables aspect ratio, and we have the freedom to change at
                least one dimension.
            */

            // Get the max possible width given our constraints
            widthSize = resolveAdjustedSize(w + pleft + pright, mMaxWidth, widthMeasureSpec);

            // Get the max possible height given our constraints
            heightSize = resolveAdjustedSize(h + ptop + pbottom, mMaxHeight, heightMeasureSpec);

            if (desiredAspect != 0.0f) {
                // See what our actual aspect ratio is
                final float actualAspect = (float) (widthSize - pleft - pright) /
                        (heightSize - ptop - pbottom);

                if (Math.abs(actualAspect - desiredAspect) > 0.0000001) {

                    boolean done = false;

                    // Try adjusting width to be proportional to height
                    if (resizeWidth) {
                        int newWidth = (int) (desiredAspect * (heightSize - ptop - pbottom)) +
                                pleft + pright;

                        // Allow the width to outgrow its original estimate if height is fixed.
                        if (!resizeHeight) {
                            widthSize = resolveAdjustedSize(newWidth, mMaxWidth, widthMeasureSpec);
                        }

                        if (newWidth <= widthSize) {
                            widthSize = newWidth;
                            done = true;
                        }
                    }

                    // Try adjusting height to be proportional to width
                    if (!done && resizeHeight) {
                        int newHeight = (int) ((widthSize - pleft - pright) / desiredAspect) +
                                ptop + pbottom;

                        // Allow the height to outgrow its original estimate if width is fixed.
                        if (!resizeWidth) {
                            heightSize = resolveAdjustedSize(newHeight, mMaxHeight,
                                    heightMeasureSpec);
                        }

                        if (newHeight <= heightSize) {
                            heightSize = newHeight;
                        }
                    }
                }
            }
        } else {
            /* We are either don't want to preserve the drawables aspect ratio,
               or we are not allowed to change view dimensions. Just measure in
               the normal way.
            */
            w += pleft + pright;
            h += ptop + pbottom;

            w = Math.max(w, getSuggestedMinimumWidth());
            h = Math.max(h, getSuggestedMinimumHeight());

            widthSize = resolveSizeAndState(w, widthMeasureSpec, 0);
            heightSize = resolveSizeAndState(h, heightMeasureSpec, 0);
        }

        setMeasuredDimension(widthSize, heightSize);
    }

    private int resolveAdjustedSize(int desiredSize, int maxSize,
                                    int measureSpec) {
        int result = desiredSize;
        final int specMode = MeasureSpec.getMode(measureSpec);
        final int specSize = MeasureSpec.getSize(measureSpec);
        switch (specMode) {
        case MeasureSpec.UNSPECIFIED:
                /* Parent says we can be as big as we want. Just don't be larger
                   than max size imposed on ourselves.
                */
            result = Math.min(desiredSize, maxSize);
            break;
        case MeasureSpec.AT_MOST:
            // Parent says we can be as big as we want, up to specSize.
            // Don't be larger than specSize, and don't be larger than
            // the max size imposed on ourselves.
            result = Math.min(Math.min(desiredSize, specSize), maxSize);
            break;
        case MeasureSpec.EXACTLY:
            // No choice. Do what we are told.
            result = specSize;
            break;
        }
        return result;
    }

    public void setAdjustViewBounds(boolean adjustViewBounds) {
        mAdjustViewBounds = adjustViewBounds;
    }

    public void setMaxWidth(int maxWidth) {
        mMaxWidth = maxWidth;
    }

    public void setMaxHeight(int maxHeight) {
        mMaxHeight = maxHeight;
    }
}

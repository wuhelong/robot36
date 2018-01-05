/*
 Copyright 2015 Ahmet Inan <xdsopl@googlemail.com>

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
import android.graphics.Bitmap;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.WorkerThread;
import android.support.v8.renderscript.Allocation;
import android.support.v8.renderscript.Element;
import android.support.v8.renderscript.RenderScript;

import java.io.Closeable;
import java.io.IOException;

public class Decoder {
    private boolean drawImage = true, quitThread = false;
    private boolean enableAnalyzer = true;
    private final Callback callback;
    @Nullable private ImageView image;
    @Nullable private final SpectrumView spectrum;
    @Nullable private final SpectrumView spectrogram;
    @Nullable private final VUMeterView meter;
    private final Input audio;

    private final int maxHeight = freeRunReserve(616);
    private final int maxWidth = 800;
    private final short[] audioBuffer;
    private final int[] pixelBuffer;
    @Nullable private final int[] spectrumBuffer;
    @Nullable private final int[] spectrogramBuffer;
    private static final int STATUS_INDEX_CURRENT_MODE  = 0;
    private static final int STATUS_INDEX_FREE_RUNNING  = 1;
    private static final int STATUS_INDEX_SAVED_WIDTH   = 2;
    private static final int STATUS_INDEX_SAVED_HEIGHT  = 3;
    private static final int STATUS_COUNT               = 4;
    private final int[] status;
    private final int[] savedBuffer;
    private final float[] volume;
    private int updateRate = 1;

    private boolean detected;
    private int mode = -1;

    private final RenderScript rs;
    private final Allocation rsDecoderAudioBuffer;
    private final Allocation rsDecoderPixelBuffer;
    @Nullable private final Allocation rsDecoderSpectrumBuffer;
    @Nullable private final Allocation rsDecoderSpectrogramBuffer;
    private final Allocation rsDecoderValueBuffer;
    private final Allocation rsDecoderStatus;
    private final Allocation rsDecoderSavedBuffer;
    private final Allocation rsDecoderVolume;
    private final ScriptC_decoder rsDecoder;

    public final static int mode_raw = 0;
    public final static int mode_robot36 = 1;
    public final static int mode_robot72 = 2;
    public final static int mode_martin1 = 3;
    public final static int mode_martin2 = 4;
    public final static int mode_scottie1 = 5;
    public final static int mode_scottie2 = 6;
    public final static int mode_scottieDX = 7;
    public final static int mode_wraaseSC2_180 = 8;
    public final static int mode_pd50 = 9;
    public final static int mode_pd90 = 10;
    public final static int mode_pd120 = 11;
    public final static int mode_pd160 = 12;
    public final static int mode_pd180 = 13;
    public final static int mode_pd240 = 14;
    public final static int mode_pd290 = 15;

    private final Thread thread = new Thread() {
        @Override
        public void run() {
            while (true) {
                synchronized (this) {
                    if (quitThread)
                        return;
                    if (drawImage) {
                        ImageView image = Decoder.this.image;
                        if (image != null)
                            image.drawCanvas();
                        if (enableAnalyzer) {
                            if (spectrum != null)
                                spectrum.drawCanvas();
                            if (spectrogram != null)
                                spectrogram.drawCanvas();
                            if (meter != null)
                                meter.drawCanvas();
                        }
                    }
                }
                decode();
            }
        }
    };

    public interface Callback {
        @WorkerThread
        void onReceived(Bitmap image);

        /**
         * When the working mode is changed
         * @param detected A valid signal was detected
         * @param mode Current working mode
         */
        @WorkerThread
        void onModeChanged(boolean detected, int mode);
    }

    public interface Input extends Closeable {
        int read(@NonNull short[] audioData, int offsetInShorts, int sizeInShorts);

        int getSampleRate();
    }

    private static class MicInput implements Input {
        private static final int audioSource = MediaRecorder.AudioSource.MIC;
        private static final int channelConfig = AudioFormat.CHANNEL_IN_MONO;
        private static final int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
        private static final int[] sampleRates = { 44100, 48000, 22050, 16000, 11025, 8000 };
        private final int sampleRate;
        private final AudioRecord audio;

        MicInput() throws RuntimeException {
            AudioRecord tmpAudio = null;
            int tmpRate = -1;
            for (int testRate : sampleRates) {
                int bufferSizeInBytes = AudioRecord.getMinBufferSize(testRate, channelConfig, audioFormat);
                if (bufferSizeInBytes <= 0)
                    continue;
                try {
                    tmpAudio = new AudioRecord(audioSource, testRate, channelConfig, audioFormat, testRate * 2);
                    if (tmpAudio.getState() == AudioRecord.STATE_INITIALIZED) {
                        tmpRate = testRate;
                        break;
                    }
                    tmpAudio.release();
                } catch (IllegalArgumentException ignore) {
                }
                tmpAudio = null;
            }
            if (tmpAudio == null)
                throw new RuntimeException("Unable to open audio.\nPlease send a bug report.");
            sampleRate = tmpRate;
            audio = tmpAudio;
            audio.startRecording();
            if (audio.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                audio.stop();
                audio.release();
                throw new RuntimeException("Unable to start recording.\nMaybe another app is recording?");
            }
        }

        @Override
        public int read(@NonNull short[] audioData, int offsetInShorts, int sizeInShorts) {
            return audio.read(audioData, offsetInShorts, sizeInShorts);
        }

        @Override
        public int getSampleRate() {
            return sampleRate;
        }

        @Override
        public void close() throws IOException {
            audio.stop();
            audio.release();
        }
    }

    public Decoder(Context context, Callback callback, @Nullable Input input) {
        this(context, callback, input, null, null, null);
    }

    public Decoder(Context context, Callback callback,
                   @Nullable Input input,
                   @Nullable SpectrumView spectrum,
                   @Nullable SpectrumView spectrogram,
                   @Nullable VUMeterView meter) {
        context = context.getApplicationContext();
        this.spectrogram = spectrogram;
        this.spectrum = spectrum;
        this.meter = meter;
        this.callback = callback;
        audio = input == null ? new MicInput() : input;
        audioBuffer = new short[audio.getSampleRate()];

        pixelBuffer = new int[maxWidth * maxHeight];
        spectrumBuffer = spectrum == null ? null : new int[spectrum.bitmap.getWidth() * spectrum.bitmap.getHeight()];
        spectrogramBuffer = spectrogram == null ? null : new int[spectrogram.bitmap.getWidth() * spectrogram.bitmap.getHeight()];

        int minValueBufferLength = 2 * audio.getSampleRate();
        int valueBufferLength = Integer.highestOneBit(minValueBufferLength);
        if (minValueBufferLength > valueBufferLength)
            valueBufferLength <<= 1;

        status = new int[STATUS_COUNT];
        volume = new float[1];
        savedBuffer = new int[pixelBuffer.length];

        rs = RenderScript.create(context);
        rsDecoderAudioBuffer = Allocation.createSized(rs, Element.I16(rs), audioBuffer.length, Allocation.USAGE_SHARED | Allocation.USAGE_SCRIPT);
        rsDecoderValueBuffer = Allocation.createSized(rs, Element.U8(rs), valueBufferLength, Allocation.USAGE_SCRIPT);
        rsDecoderPixelBuffer = Allocation.createSized(rs, Element.I32(rs), pixelBuffer.length, Allocation.USAGE_SHARED | Allocation.USAGE_SCRIPT);
        rsDecoderSpectrumBuffer = spectrumBuffer == null ? null : Allocation.createSized(rs, Element.I32(rs), spectrumBuffer.length, Allocation.USAGE_SHARED | Allocation.USAGE_SCRIPT);
        rsDecoderSpectrogramBuffer = spectrogramBuffer == null ? null : Allocation.createSized(rs, Element.I32(rs), spectrogramBuffer.length, Allocation.USAGE_SHARED | Allocation.USAGE_SCRIPT);
        rsDecoderStatus = Allocation.createSized(rs, Element.I32(rs), STATUS_COUNT, Allocation.USAGE_SHARED | Allocation.USAGE_SCRIPT);
        rsDecoderVolume = Allocation.createSized(rs, Element.F32(rs), 1, Allocation.USAGE_SHARED | Allocation.USAGE_SCRIPT);
        rsDecoderSavedBuffer = Allocation.createSized(rs, Element.I32(rs), savedBuffer.length, Allocation.USAGE_SHARED | Allocation.USAGE_SCRIPT);
        rsDecoder = new ScriptC_decoder(rs);
        rsDecoder.bind_audio_buffer(rsDecoderAudioBuffer);
        rsDecoder.bind_value_buffer(rsDecoderValueBuffer);
        rsDecoder.bind_pixel_buffer(rsDecoderPixelBuffer);
        if (rsDecoderSpectrumBuffer != null)
            rsDecoder.bind_spectrum_buffer(rsDecoderSpectrumBuffer);
        if (rsDecoderSpectrogramBuffer != null)
            rsDecoder.bind_spectrogram_buffer(rsDecoderSpectrogramBuffer);
        rsDecoder.bind_status(rsDecoderStatus);
        rsDecoder.bind_volume(rsDecoderVolume);
        rsDecoder.bind_saved_buffer(rsDecoderSavedBuffer);
        rsDecoder.invoke_initialize(audio.getSampleRate(), valueBufferLength, maxWidth, maxHeight,
                spectrum == null ? 0 : spectrum.bitmap.getWidth(),
                spectrum == null ? 0 : spectrum.bitmap.getHeight(),
                spectrogram == null ? 0 : spectrogram.bitmap.getWidth(),
                spectrogram == null ? 0 : spectrogram.bitmap.getHeight());
        if (spectrum == null)
            enable_analyzer(false);
        thread.start();
    }

    public void setImageView(ImageView image) {
        updateImageResolution(image);
        this.image = image;
    }

    void clear_image() { rsDecoder.invoke_reset_buffer(); }
    void toggle_scaling() { image.intScale ^= true; }
    void adjust_blur(int blur) { rsDecoder.invoke_adjust_blur(blur); }
    void toggle_debug() { rsDecoder.invoke_toggle_debug(); }
    void enable_analyzer(boolean enable) {
        if (spectrum == null)
            enable = false;
        rsDecoder.invoke_enable_analyzer((enableAnalyzer = enable) ? 1 : 0);
    }
    void auto_mode() { rsDecoder.invoke_auto_mode(1); }
    void raw_mode() { rsDecoder.invoke_auto_mode(0); rsDecoder.invoke_raw_mode(); }
    void robot36_mode() { rsDecoder.invoke_auto_mode(0); rsDecoder.invoke_robot36_mode(); }
    void robot72_mode() { rsDecoder.invoke_auto_mode(0); rsDecoder.invoke_robot72_mode(); }
    void martin1_mode() { rsDecoder.invoke_auto_mode(0); rsDecoder.invoke_martin1_mode(); }
    void martin2_mode() { rsDecoder.invoke_auto_mode(0); rsDecoder.invoke_martin2_mode(); }
    void scottie1_mode() { rsDecoder.invoke_auto_mode(0); rsDecoder.invoke_scottie1_mode(); }
    void scottie2_mode() { rsDecoder.invoke_auto_mode(0); rsDecoder.invoke_scottie2_mode(); }
    void scottieDX_mode() { rsDecoder.invoke_auto_mode(0); rsDecoder.invoke_scottieDX_mode(); }
    void wraaseSC2_180_mode() { rsDecoder.invoke_auto_mode(0); rsDecoder.invoke_wraaseSC2_180_mode(); }
    void pd50_mode() { rsDecoder.invoke_auto_mode(0); rsDecoder.invoke_pd50_mode(); }
    void pd90_mode() { rsDecoder.invoke_auto_mode(0); rsDecoder.invoke_pd90_mode(); }
    void pd120_mode() { rsDecoder.invoke_auto_mode(0); rsDecoder.invoke_pd120_mode(); }
    void pd160_mode() { rsDecoder.invoke_auto_mode(0); rsDecoder.invoke_pd160_mode(); }
    void pd180_mode() { rsDecoder.invoke_auto_mode(0); rsDecoder.invoke_pd180_mode(); }
    void pd240_mode() { rsDecoder.invoke_auto_mode(0); rsDecoder.invoke_pd240_mode(); }
    void pd290_mode() { rsDecoder.invoke_auto_mode(0); rsDecoder.invoke_pd290_mode(); }

    int freeRunReserve(int height) {
        if (detected)
            return height;
        return (height * 3) / 2;
    }
    void setUpdateRate(int rate) { updateRate = Math.max(0, Math.min(4, rate)); }

    void switch_mode(boolean detected, int mode)
    {
        if (this.mode == mode && this.detected == detected)
            return;
        this.detected = detected;
        this.mode = mode;
        callback.onModeChanged(detected, mode);
        updateImageResolution(image);
    }

    private void updateImageResolution(ImageView image) {
        if (image == null)
            return;
        switch (mode) {
            case mode_raw:
                image.setImageResolution(maxWidth, maxHeight);
                break;
            case mode_robot36:
                image.setImageResolution(320, freeRunReserve(240));
                break;
            case mode_robot72:
                image.setImageResolution(320, freeRunReserve(240));
                break;
            case mode_martin1:
                image.setImageResolution(320, freeRunReserve(256));
                break;
            case mode_martin2:
                image.setImageResolution(320, freeRunReserve(256));
                break;
            case mode_scottie1:
                image.setImageResolution(320, freeRunReserve(256));
                break;
            case mode_scottie2:
                image.setImageResolution(320, freeRunReserve(256));
                break;
            case mode_scottieDX:
                image.setImageResolution(320, freeRunReserve(256));
                break;
            case mode_wraaseSC2_180:
                image.setImageResolution(320, freeRunReserve(256));
                break;
            case mode_pd50:
                image.setImageResolution(320, freeRunReserve(256));
                break;
            case mode_pd90:
                image.setImageResolution(320, freeRunReserve(256));
                break;
            case mode_pd120:
                image.setImageResolution(640, freeRunReserve(496));
                break;
            case mode_pd160:
                image.setImageResolution(512, freeRunReserve(400));
                break;
            case mode_pd180:
                image.setImageResolution(640, freeRunReserve(496));
                break;
            case mode_pd240:
                image.setImageResolution(640, freeRunReserve(496));
                break;
            case mode_pd290:
                image.setImageResolution(800, freeRunReserve(616));
                break;
            default:
                break;
        }
    }

    public void pause() {
        synchronized (thread) {
            drawImage = false;
        }
    }

    public void resume() {
        synchronized (thread) {
            drawImage = true;
        }
    }

    public void destroy() {
        synchronized (thread) {
            quitThread = true;
        }
        try {
            audio.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        try {
            thread.join();
        } catch (InterruptedException ignore) {
        }
    }

    void decode() {
        int samples = audio.read(audioBuffer, 0, audioBuffer.length >> updateRate);
        if (samples <= 0)
            return;

        rsDecoderAudioBuffer.copyFrom(audioBuffer);
        rsDecoder.invoke_decode(samples);

        rsDecoderStatus.copyTo(status);
        switch_mode(status[STATUS_INDEX_FREE_RUNNING] == 0, status[STATUS_INDEX_CURRENT_MODE]);

        ImageView image = this.image;
        if (image != null) {
            rsDecoderPixelBuffer.copyTo(pixelBuffer);
            image.setPixels(pixelBuffer);
        }

        if (meter != null) {
            rsDecoderVolume.copyTo(volume);
            meter.volume = volume[0];
        }

        saveImage();

        if (enableAnalyzer) {
            if (spectrum != null) {
                rsDecoderSpectrumBuffer.copyTo(spectrumBuffer);
                spectrum.bitmap.setPixels(spectrumBuffer, 0, spectrum.bitmap.getWidth(), 0, 0, spectrum.bitmap.getWidth(), spectrum.bitmap.getHeight());
            }
            if (spectrogram != null) {
                rsDecoderSpectrogramBuffer.copyTo(spectrogramBuffer);
                spectrogram.bitmap.setPixels(spectrogramBuffer, 0, spectrogram.bitmap.getWidth(), 0, 0, spectrogram.bitmap.getWidth(), spectrogram.bitmap.getHeight());
            }
        }
    }

    private void saveImage() {
        if (status[STATUS_INDEX_SAVED_HEIGHT] > 0 && status[STATUS_INDEX_SAVED_WIDTH] > 0) {
            rsDecoderSavedBuffer.copyTo(savedBuffer);
            callback.onReceived(Bitmap.createBitmap(savedBuffer,
                    status[STATUS_INDEX_SAVED_WIDTH],
                    status[STATUS_INDEX_SAVED_HEIGHT],
                    Bitmap.Config.ARGB_8888));
        }
    }

    /**
     * Save the image even if it has not finished receiving.
     */
    public void save() {
        if (!detected)
            return;
        rsDecoder.invoke_save_buffer();
        rsDecoderStatus.copyTo(status);
        saveImage();
        switch_mode(false, mode);
    }

    public boolean isDetected() {
        return detected;
    }

    public int getMode() {
        return mode;
    }
}
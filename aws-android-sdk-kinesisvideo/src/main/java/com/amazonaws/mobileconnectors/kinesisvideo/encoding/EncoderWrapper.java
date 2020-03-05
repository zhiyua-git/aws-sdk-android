/**
 * Copyright 2017-2018 Amazon.com,
 * Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Amazon Software License (the "License").
 * You may not use this file except in compliance with the
 * License. A copy of the License is located at
 *
 *     http://aws.amazon.com/asl/
 *
 * or in the "license" file accompanying this file. This file is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, express or implied. See the License
 * for the specific language governing permissions and
 * limitations under the License.
 */

package com.amazonaws.mobileconnectors.kinesisvideo.encoding;

import android.media.Image;
import android.media.MediaCodec;
import android.util.Log;

import com.amazonaws.kinesisvideo.client.mediasource.AudioMediaSourceConfiguration;
import com.amazonaws.kinesisvideo.client.mediasource.CameraMediaSourceConfiguration;
import com.amazonaws.kinesisvideo.common.exception.KinesisVideoException;
import com.amazonaws.kinesisvideo.internal.client.mediasource.MediaSourceConfiguration;
import com.amazonaws.kinesisvideo.internal.mediasource.OnStreamDataAvailable;
import com.amazonaws.kinesisvideo.producer.KinesisVideoFrame;
import com.amazonaws.mobileconnectors.kinesisvideo.util.FrameUtility;

import java.nio.ByteBuffer;

import static com.amazonaws.kinesisvideo.util.StreamInfoConstants.VIDEO_TRACK_ID;

/**
 * Wrapper class around MediaCodec.
 * Accepts raw frame data in YUV420 format as an input, sends it to the encoder,
 * notifies the listeners when encoding is complete.
 * All happens on the same thread
 */
public class EncoderWrapper {
    private static final String TAG = EncoderWrapper.class.getSimpleName();
    private static final int TIMEOUT_USEC = 10000;
    private final MediaSourceConfiguration mMediaSourceConfiguration;
    private MediaCodec mEncoder;
    private EncoderFrameSubmitter mEncoderFrameSubmitter;
    private EncoderSampleSubmitter mEncoderSampleSubmitter;
    private long mLastRecordedFrameTimestamp = 0;
    private MediaCodec.BufferInfo mBufferInfo;
    private CodecPrivateDataAvailableListener mCodecPrivateDataListener;
    private FrameAvailableListener mFrameAvailableListener;
    private OnStreamDataAvailable mListener = null;
    private boolean mIsStopped = false;
    private int mFrameIndex;
    private long mFragmentStart = 0;
    private int mTrackId = 0;
    private Object mStartMutex = null;

    public interface FrameAvailableListener {

        void onFrameAvailable(final KinesisVideoFrame frame);
    }
    public interface CodecPrivateDataAvailableListener {

        void onCodecPrivateDataAvailable(final byte[] privateData);
    }
    public EncoderWrapper(final MediaSourceConfiguration mediaSourceConfiguration) {
        mMediaSourceConfiguration = mediaSourceConfiguration;
        initEncoder();
    }

    public EncoderWrapper(final MediaSourceConfiguration mediaSourceConfiguration, final Object startMutex) {
        mMediaSourceConfiguration = mediaSourceConfiguration;
        initEncoder();
        mStartMutex = startMutex;
    }

    private void initEncoder() {
        mBufferInfo = new MediaCodec.BufferInfo();
        if (mMediaSourceConfiguration instanceof CameraMediaSourceConfiguration) {
            mTrackId = ((CameraMediaSourceConfiguration) mMediaSourceConfiguration).getTrackId();
            mEncoder = EncoderFactory
                    .createConfiguredEncoder((CameraMediaSourceConfiguration) mMediaSourceConfiguration);
            mEncoderFrameSubmitter = new EncoderFrameSubmitter(mEncoder);
        } else if (mMediaSourceConfiguration instanceof AudioMediaSourceConfiguration) {
            mTrackId = ((AudioMediaSourceConfiguration) mMediaSourceConfiguration).getTrackId();
            mEncoder = EncoderFactory
                    .createConfiguredAudioEncoder((AudioMediaSourceConfiguration) mMediaSourceConfiguration);
            mEncoderSampleSubmitter = new EncoderSampleSubmitter(mEncoder);
        } else {
            throw new RuntimeException("Unexpected media source configuration: " + mMediaSourceConfiguration);
        }
        mEncoder.start();
    }

    public void setMkvDataListener(final OnStreamDataAvailable listener) {
        mListener = listener;
    }

    public void setCodecPrivateDataAvailableListener(
            final CodecPrivateDataAvailableListener listener) {

        mCodecPrivateDataListener = listener;
    }

    public void setEncodedFrameAvailableListener(final FrameAvailableListener listener) {
        mFrameAvailableListener = listener;
    }

    public void encodeFrame(final Image frameImageYUV420,
                            final boolean endOfStream) {

        if (mIsStopped) {
            Log.w(TAG, "received a frame to encode after already stopped. returning");
            return;
        }

        Log.d(TAG, "encoding frame" + threadId());

        mEncoderFrameSubmitter.submitFrameToEncoder(frameImageYUV420, endOfStream);

        Log.d(TAG, "frame sent to encoder" + threadId());

        getDataFromEncoder(endOfStream);

        Log.d(TAG, "frame encoded" + threadId());
    }

    public void encodeSample(final ByteBuffer buffer,
                             final int readBytes,
                             final boolean endOfStream) {

        if (mIsStopped) {
            Log.w(TAG, "received a sample to encode after already stopped. returning");
            return;
        }

        Log.d(TAG, "encoding sample" + threadId());

        mEncoderSampleSubmitter.submitSampleToEncoder(buffer, readBytes, endOfStream);

        Log.d(TAG, "sample sent to encoder" + threadId());

        getDataFromEncoder(endOfStream);

        Log.d(TAG, "sample encoded" + threadId());
    }


    private void getDataFromEncoder(final boolean endOfStream) {
        boolean stopReadingFromEncoder = false;
        while(!stopReadingFromEncoder) {
            mBufferInfo = new MediaCodec.BufferInfo();
            int outputBufferId = mEncoder.dequeueOutputBuffer(mBufferInfo, TIMEOUT_USEC);
            switch (outputBufferId) {
                case MediaCodec.INFO_TRY_AGAIN_LATER:
                    if (endOfStream) {
                        Log.d(TAG, "no output available, await end of stream");
                        sleep(15);
                    }
                    stopReadingFromEncoder = true;
                    break;
                case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
                    break;
                default:
                    if (outputBufferId < 0) {
                        Log.w(TAG, "unexpected encoder output buffer id: " + outputBufferId);
                        break;
                    }

                    processEncoderOutputBuffer(outputBufferId);

                    if (isEndOfStream()) {
                        stopReadingFromEncoder = true;
                    }

                    break;
            }
        }
    }

    private void processEncoderOutputBuffer(final int outputBufferId) {
        if (mBufferInfo.size == 0) {
            Log.w(TAG, "empty buffer " + outputBufferId);
            mEncoder.releaseOutputBuffer(outputBufferId, false);
            return;
        }

        final ByteBuffer encodedData = mEncoder.getOutputBuffer(outputBufferId);

        if (encodedData == null) {
            throw new RuntimeException("encoder output buffer " + outputBufferId + " is null");
        }

        processEncodedData(encodedData);

        mEncoder.releaseOutputBuffer(outputBufferId, false);
    }

    private void processEncodedData(final ByteBuffer encodedData) {
        adjustEncodedDataPosition(encodedData);
        adjustEncodedDataPosition(encodedData);

        if (isCodecPrivateData()) {
            notifyCodecPrivateDataAvailable(encodedData);
            return;
        }

        if (isEndOfStream()) {
            Log.d(TAG, "end of stream reached");
            return;
        }

        sendEncodedFrameToProducerSDK(encodedData);
    }

    private void adjustEncodedDataPosition(final ByteBuffer encodedData) {
        encodedData.position(mBufferInfo.offset);
        encodedData.limit(mBufferInfo.offset + mBufferInfo.size);
    }

    private boolean isEndOfStream() {
        return (mBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
    }

    private boolean isCodecPrivateData() {
        return (mBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
    }

    private void notifyCodecPrivateDataAvailable(final ByteBuffer codecPrivateDataBuffer) {
        Log.d(TAG, "got codec private data");
        final ByteBuffer privateData = codecPrivateDataBuffer;
        final byte[] codecPrivateDataArray = convertToArray(privateData);
        if (mListener == null) {
            mCodecPrivateDataListener.onCodecPrivateDataAvailable(codecPrivateDataArray);
        } else {
            try {
                mListener.onCodecPrivateData(codecPrivateDataArray, mTrackId);
            } catch (KinesisVideoException e) {
                Log.e(TAG, "error updating sink with codec private data", e);
                throw new RuntimeException("error updating sink with codec private data", e);
            }
        }
    }

    private void sendEncodedFrameToProducerSDK(final ByteBuffer encodedData) {
        final long currentTime = System.currentTimeMillis();
        Log.d(TAG, "time between frames: " + (currentTime - mLastRecordedFrameTimestamp) + "ms");
        mLastRecordedFrameTimestamp = currentTime;

        if (mFragmentStart == 0) {
            mFragmentStart = currentTime;
            if (mStartMutex != null) {
                synchronized (mStartMutex) {
                    // Notify others this source started
                    mStartMutex.notifyAll();
                }
            }
        }

        final ByteBuffer frameData = encodedData;

        if (mListener == null) {
            mFrameAvailableListener.onFrameAvailable(
                    FrameUtility.createFrame(
                            mBufferInfo,
                            1 + currentTime - mFragmentStart,
                            mFrameIndex++,
                            frameData));
        } else {
            try {
                mListener.onFrameDataAvailable(FrameUtility.createFrame(
                        mBufferInfo,
                        1 + currentTime - mFragmentStart,
                        mFrameIndex++,
                        frameData,
                        mTrackId,
                        mMediaSourceConfiguration instanceof AudioMediaSourceConfiguration));
            } catch (KinesisVideoException e) {
                Log.e(TAG, "error updating sink with frame", e);
            }
        }
    }

    public void stop() {
        Log.d(TAG, "stopping encoder");
        mIsStopped = true;
        mEncoder.stop();
        mEncoder.release();
    }

    private byte[] convertToArray(final ByteBuffer byteBuffer) {
        final byte[] array = new byte[byteBuffer.remaining()];
        byteBuffer.get(array);
        return array;
    }

    private static String threadId() {
        return " | threadId=" + Thread.currentThread().getId();
    }

    private static void sleep(final int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}

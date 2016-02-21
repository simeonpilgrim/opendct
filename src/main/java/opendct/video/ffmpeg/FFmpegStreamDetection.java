/*
 * Copyright 2016 The OpenDCT Authors. All Rights Reserved
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package opendct.video.ffmpeg;

import opendct.config.Config;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bytedeco.javacpp.PointerPointer;
import org.bytedeco.javacpp.avcodec;
import org.bytedeco.javacpp.avutil;

import static opendct.video.ffmpeg.FFmpegUtil.*;
import static org.bytedeco.javacpp.avformat.*;
import static org.bytedeco.javacpp.avutil.*;

public class FFmpegStreamDetection {
    private static final Logger logger = LogManager.getLogger(FFmpegStreamDetection.class);

    //TODO: Provide all of these as DeviceOptions.

    // This is the smallest probe size allowed.
    private static long minProbeSize =
            Math.max(
                    Config.getInteger("consumer.ffmpeg.min_probe_size", 165440),
                    82720
            );

    // This is the smallest probe duration allowed.
    private static long minAnalyzeDuration =
            Math.max(
                    Config.getInteger("consumer.ffmpeg.min_analyze_duration", 165440),
                    82720
            );

    // This is the largest analyze duration allowed. 5,000,000 is the minimum allowed value.
    private static long maxAnalyzeDuration =
            Math.max(
                    Config.getInteger("consumer.ffmpeg.max_analyze_duration", 5000000),
                    5000000
            );

    /**
     * Detect at least one video and all audio streams for a program.
     * <p/>
     * If the FFmpeg context provided has a desired program in it, this detection method will focus
     * on getting at least one video and all audio streams in that program. Otherwise it will return
     * a best effort after exhausting the maximum probe size.
     *
     * @param ctx The FFmpeg context to be used for the stream detection.
     * @param nativeFilename The filename to be read if native mode is enabled. Otherwise this can
     *                       be <i>null</i>.
     * @param error An array with at a length of at least 1. If <i>false</i> is returned, an error
     *              message will be put in this array. If no array was provided or had a length of
     *              0, no error can be returned.
     * @return <i>true</i> if at least one video and all audio streams in a program have been
     *         detected.
     * @throws IllegalStateException Thrown if there are any problems allocating contexts. All
     *                               contexts created by this function will already be de-allocated
     *                               before the exception is returned.
     */
    public static boolean detectStreams(FFmpegContext ctx, String nativeFilename, String error[]) throws IllegalStateException {

        if (error == null || error.length == 0) {
            // Nothing will return in this case, but this also means we don't need to check for this
            // condition throughout the detection process.
            error = new String[1];
        }

        ctx.setProbeData(nativeFilename);

        long dynamicProbeSize = minProbeSize;
        long dynamicAnalyzeDuration = minAnalyzeDuration;
        final long probeSizeLimit = Math.max(ctx.getProbeMaxSize() - 1123474, 1123474);
        final long analyzeDurationLimit = maxAnalyzeDuration;

        ctx.SEEK_BUFFER.setNoWrap(true);

        long startNanoTime = System.nanoTime();

        while (true) {

            int ret;

            //
            // A new input AVFormatContext must be created for each avformat_find_stream_info probe or the JVM will crash.
            // avformat_open_input seems to even use the probe size/max analyze duration values so they're set too
            // before the avformat_find_stream_info() call.
            // Although the AVIOContext does not need to be allocated/freed for each probe it is done so the counters
            // for bytes read, seeks done, etc are reset.
            //

            switch (ctx.inputFileMode) {
                case FFmpegContext.FILE_MODE_MPEGTS:
                    ctx.allocInputIoTsFormatContext();

                    break;
                case FFmpegContext.FILE_MODE_NATIVE:
                    ctx.allocInputFormatNativeContext(nativeFilename, null);
                    break;
                default:
                    error[0] = "Invalid file input mode selected: " + ctx.inputFileMode;
                    return false;
            }

            if (ctx.isInterrupted()) {
                error[0] = FFMPEG_INIT_INTERRUPTED;
                return false;
            }

            // By adding 188 to the available bytes, we can be reasonably sure we will not return
            // here until the available data has increased.
            dynamicProbeSize = Math.max(dynamicProbeSize, ctx.getProbeAvailable() + 188);
            dynamicProbeSize = Math.min(dynamicProbeSize, probeSizeLimit);

            dynamicAnalyzeDuration = Math.max(dynamicAnalyzeDuration, (System.nanoTime() - startNanoTime) / 1000L);
            dynamicAnalyzeDuration = Math.min(dynamicAnalyzeDuration, analyzeDurationLimit);

            av_opt_set_int(ctx.avfCtxInput.priv_data(), "probesize", dynamicProbeSize, 0); // Must be set before avformat_open_input
            av_opt_set_int(ctx.avfCtxInput.priv_data(), "analyzeduration", dynamicAnalyzeDuration, 0); // Must be set before avformat_find_stream_info

            logger.debug("Calling avformat_open_input");

            ret = avformat_open_input(ctx.avfCtxInput, ctx.inputFilename, null, null);
            if (ret != 0) {
                error[0] = "avformat_open_input returned error code " + ret;
                return false;
            }

            logger.info("Before avformat_find_stream_info() pos={} bytes_read={} seek_count={}. probesize: {} analyzeduration: {}.",
                    ctx.avioCtxInput.pos(), ctx.avioCtxInput.bytes_read(), ctx.avioCtxInput.seek_count(), dynamicProbeSize, dynamicAnalyzeDuration);
            ret = avformat_find_stream_info(ctx.avfCtxInput, (PointerPointer<avutil.AVDictionary>) null);
            logger.info("After avformat_find_stream_info() pos={} bytes_read={} seek_count={}. probesize: {} analyzeduration: {}.",
                    ctx.avioCtxInput.pos(), ctx.avioCtxInput.bytes_read(), ctx.avioCtxInput.seek_count(), dynamicProbeSize, dynamicAnalyzeDuration);

            if (ret < 0) {
                error[0] = "avformat_find_stream_info() failed with error code " + -ret + ".";
                if (dynamicProbeSize == probeSizeLimit) {
                    return false;
                }
                logger.info(error[0] + " Trying again with more data.");

                // When we allocate the new context, the old context will de-allocate automatically.
                //freeAndSetNullAttemptData();
                continue;
            }

            if (ctx.isInterrupted()) {
                error[0] = FFMPEG_INIT_INTERRUPTED;
                return false;
            }

            // While we haven't seen all streams for the desired program and we haven't exhausted our attempts, try again...
            if (!FFmpegUtil.findAllStreamsForDesiredProgram(ctx.avfCtxInput, ctx.desiredProgram) && dynamicProbeSize != probeSizeLimit) {
                logger.info("Stream details unavailable for one or more streams. " + TRYING_AGAIN);

                // When we allocate the new context, the old context will de-allocate automatically.
                //freeAndSetNullAttemptData();
                continue;
            }

            ctx.preferredVideo = av_find_best_stream(ctx.avfCtxInput, AVMEDIA_TYPE_VIDEO, NO_STREAM_IDX, NO_STREAM_IDX, (PointerPointer<avcodec.AVCodec>) null, 0);

            if (ctx.preferredVideo != AVERROR_STREAM_NOT_FOUND) {
                ctx.videoCodecCtx = getCodecContext(ctx.avfCtxInput.streams(ctx.preferredVideo));
            }

            if (ctx.videoCodecCtx == null) {
                if (ctx.isInterrupted()) {
                    error[0] = FFMPEG_INIT_INTERRUPTED;
                    return false;
                }

                error[0] = "Could not find a video stream.";
                if (dynamicProbeSize == probeSizeLimit) {
                    return false;
                }
                logger.info(error[0] + TRYING_AGAIN);

                // When we allocate the new context, the old context will de-allocate automatically.
                //freeAndSetNullAttemptData();
                continue;
            }

            if (ctx.isInterrupted()) {
                error[0] = FFMPEG_INIT_INTERRUPTED;
                return false;
            }

            ctx.preferredAudio = findBestAudioStream(ctx.avfCtxInput);

            if (ctx.preferredAudio != AVERROR_STREAM_NOT_FOUND) {
                ctx.audioCodecCtx = getCodecContext(ctx.avfCtxInput.streams(ctx.preferredAudio));
            }

            if (ctx.audioCodecCtx == null) {
                if (ctx.isInterrupted()) {
                    error[0] = FFMPEG_INIT_INTERRUPTED;
                    return false;
                }

                error[0] = "Could not find an audio stream.";
                if (dynamicProbeSize == probeSizeLimit) {
                    return false;
                }
                logger.info(error[0] + TRYING_AGAIN);

                // When we allocate the new context, the old context will de-allocate automatically.
                //freeAndSetNullAttemptData();
                continue;
            }

            break;
        }

        if (ctx.isInterrupted()) {
            error[0] = FFMPEG_INIT_INTERRUPTED;
            return false;
        }

        ctx.SEEK_BUFFER.setNoWrap(false);

        return true;
    }

    public static long getMinProbeSize() {
        return minProbeSize;
    }

    public static void setMinProbeSize(long minProbeSize) {
        FFmpegStreamDetection.minProbeSize = minProbeSize;
    }

    public static long getMinAnalyzeDuration() {
        return minAnalyzeDuration;
    }

    public static void setMinAnalyzeDuration(long minAnalyzeDuration) {
        FFmpegStreamDetection.minAnalyzeDuration = minAnalyzeDuration;
    }

    public static long getMaxAnalyzeDuration() {
        return maxAnalyzeDuration;
    }

    public static void setMaxAnalyzeDuration(long maxAnalyzeDuration) {
        FFmpegStreamDetection.maxAnalyzeDuration = maxAnalyzeDuration;
    }
}
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
import opendct.config.options.*;
import opendct.nanohttpd.pojo.JsonOption;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class FFmpegConfig {
    private static final Logger logger = LogManager.getLogger(FFmpegConfig.class);

    private static final Map<String, DeviceOption> deviceOptions;

    private static BooleanDeviceOption uploadIdEnabled;
    private static IntegerDeviceOption circularBufferSize;
    private static IntegerDeviceOption minProbeSize;
    private static IntegerDeviceOption minAnalyseDuration;
    private static IntegerDeviceOption maxAnalyseDuration;
    private static IntegerDeviceOption rwBufferSize;
    private static IntegerDeviceOption minUploadIdTransferSize;
    private static IntegerDeviceOption threadPriority;
    private static IntegerDeviceOption uploadIdPort;
    private static BooleanDeviceOption fixStream;
    private static BooleanDeviceOption useCompatibilityTimebase;
    private static IntegerDeviceOption noProgramTimeout;
    private static BooleanDeviceOption ccExtractor;
    private static BooleanDeviceOption ccExtractorAllStreams;
    private static StringDeviceOption ccExtractorCustomOptions;

    static {
        deviceOptions = new ConcurrentHashMap<>();

        initDeviceOptions();

        Config.mapDeviceOptions(
                deviceOptions,
                uploadIdEnabled,
                circularBufferSize,
                minProbeSize,
                minAnalyseDuration,
                maxAnalyseDuration,
                rwBufferSize,
                minUploadIdTransferSize,
                threadPriority,
                uploadIdPort,
                fixStream,
                useCompatibilityTimebase,
                noProgramTimeout,
                ccExtractor,
                ccExtractorAllStreams,
                ccExtractorCustomOptions
        );
    }

    private static void initDeviceOptions() {

        while (true) {
            try {
                uploadIdEnabled = new BooleanDeviceOption(
                        Config.getBoolean("consumer.ffmpeg.upload_id_enabled", true),
                        false,
                        "Enable Upload ID",
                        "consumer.ffmpeg.upload_id_enabled",
                        "This enables the use of upload ID with SageTV for writing out recordings.");

                circularBufferSize = new IntegerDeviceOption(
                        Config.getInteger("consumer.ffmpeg.circular_buffer_size", 7864320),
                        false,
                        "Circular Buffer Size",
                        "consumer.ffmpeg.circular_buffer_size",
                        "This is the starting size of the circular buffer. The buffer can grow up" +
                                " to 3 times its initial size during stream detection. Once" +
                                " stream detection is done, the buffer will not get any larger." +
                                " This value cannot be less than 6740844 bytes and cannot be" +
                                " greater than 33554432 bytes.",
                        6740844,
                        33554432);

                minProbeSize = new IntegerDeviceOption(
                        Config.getInteger("consumer.ffmpeg.min_probe_size", 165440),
                        false,
                        "Minimum Probe Size",
                        "consumer.ffmpeg.min_probe_size",
                        "This is the smallest amount of data in bytes to be probed. Increase this" +
                                " size if you are noticing very bad CPU spikes when starting" +
                                " recordings. This value cannot be less than 82720 and cannot" +
                                " exceed 6740844.",
                        82720,
                        6740844);

                minAnalyseDuration = new IntegerDeviceOption(
                        Config.getInteger("consumer.ffmpeg.min_analyze_duration", 165440),
                        false,
                        "Minimum Analyze Duration",
                        "consumer.ffmpeg.min_analyze_duration",
                        "This is the shortest amount of time in microseconds that FFmpeg will" +
                                " probe the stream. This value cannot be less than 82720 and" +
                                " cannot be greater than 5000000.",
                        82720,
                        5000000);

                maxAnalyseDuration = new IntegerDeviceOption(
                        Config.getInteger("consumer.ffmpeg.max_analyze_duration", 5000000),
                        false,
                        "Maximum Analyze Duration",
                        "consumer.ffmpeg.max_analyze_duration",
                        "This is the longest amount of time in microseconds that FFmpeg will" +
                                " probe the stream. This value cannot be less than 5000000 and" +
                                " cannot be greater than 10000000.",
                        5000000,
                        10000000);

                rwBufferSize = new IntegerDeviceOption(
                        Config.getInteger("consumer.ffmpeg.rw_buffer_size", 65536),
                        false,
                        "Read/Write Transfer Buffer Size",
                        "consumer.ffmpeg.rw_buffer_size",
                        "This is the size of the buffers in bytes to be created for reading data" +
                                " into and writing data out of FFmpeg to disk. The consumer will" +
                                " use 2-3 times this value in memory. This value cannot be less" +
                                " than 65536 and cannot be greater than 1048576.",
                        65536,
                        1048576);

                minUploadIdTransferSize = new IntegerDeviceOption(
                        Config.getInteger("consumer.ffmpeg.min_upload_id_transfer_size", 65536),
                        false,
                        "Minimum Upload ID Transfer Size",
                        "consumer.ffmpeg.min_upload_id_transfer_size",
                        "This is the minimum amount of data that must be collected from FFmpeg" +
                                " before it will be sent over to SageTV. If this value is less" +
                                " than the Read/Write Transfer Buffer Size, this value will be" +
                                " increased to match it. This value cannot be less than 65536 and" +
                                " cannot be greater than 1048576.",
                        65536,
                        1048576);

                threadPriority = new IntegerDeviceOption(
                        Config.getInteger("consumer.ffmpeg.thread_priority", Thread.MAX_PRIORITY - 1),
                        false,
                        "FFmpeg Thread Priority",
                        "consumer.ffmpeg.thread_priority",
                        "This is the priority given to the FFmpeg processing thread. A higher" +
                                " number means higher priority. Only change this value for" +
                                " troubleshooting. This value cannot be less than 1 and cannot be" +
                                " greater than 10.",
                        Thread.MIN_PRIORITY,
                        Thread.MAX_PRIORITY);

                uploadIdPort = new IntegerDeviceOption(
                        Config.getInteger("consumer.ffmpeg.upload_id_port", 7818),
                        false,
                        "SageTV Upload ID Port",
                        "consumer.ffmpeg.upload_id_port",
                        "This is the port number used to communicate with SageTV when using" +
                                " upload ID for recording. You only need to change this value if" +
                                " you have changed it in SageTV. This value cannot be less than" +
                                " 1024 and cannot be greater than 65535.",
                        1024,
                        65535);

                fixStream = new BooleanDeviceOption(
                        Config.getBoolean("consumer.ffmpeg.fix_stream", true),
                        false,
                        "Fix Stream",
                        "consumer.ffmpeg.fix_stream",
                        "This enables discontinuity repairs. If you are experiencing excessive" +
                                " logging regarding these repairs, you can try disabling this. Be" +
                                " warned that if your provider does anything significantly wrong" +
                                " regarding the stream continuity, you may end up with bad" +
                                " recordings at times."
                );

                useCompatibilityTimebase = new BooleanDeviceOption(
                        Config.getBoolean("consumer.ffmpeg.use_compat_timebase", false),
                        false,
                        "Use 1/0 Timebase for Streams",
                        "consumer.ffmpeg.use_compat_timebase",
                        "This enables using the timebase 1/0 on the stream for each codec. This" +
                                " is a compatibility option that fixes the stream interleaving Only" +
                                " enable this option if you are having issues with playback" +
                                " outside of SageTV."
                );

                noProgramTimeout = new IntegerDeviceOption(
                        Config.getInteger("consumer.ffmpeg.no_program_timeout_ms", 10000),
                        false,
                        "No Program Timeout",
                        "consumer.ffmpeg.no_program_timeout_ms",
                        "This is the number of milliseconds to wait without successfully finding" +
                                " a program in the stream until it is assumed that a program does" +
                                " not exist. A program is used to ensure we have detected all" +
                                " video and audio streams related to the program. After this" +
                                " time as long as there is a video and an audio stream, remuxing" +
                                " will start with what has been detected. Note that low values" +
                                " will increase the likelihood that programs with more than one" +
                                " audio stream will be incorrectly remuxed with only one audio" +
                                " stream. This value cannot be less than 5000 and cannot exceed" +
                                " 60000.",
                        5000,
                        60000);

                ccExtractor = new BooleanDeviceOption(
                        Config.getBoolean("consumer.ffmpeg.ccextractor_enabled", false),
                        false,
                        "Enable CCExtractor",
                        "consumer.ffmpeg.ccextractor_enabled",
                        "This enables the creation of .srt files live while recording. This" +
                                " option will not do anything when Upload ID is enabled."
                );

                ccExtractorAllStreams = new BooleanDeviceOption(
                        Config.getBoolean("consumer.ffmpeg.ccextractor_all_streams", true),
                        false,
                        "Extract All CCExtractor Streams",
                        "consumer.ffmpeg.ccextractor_all_streams",
                        "This enables the creation of all possible .srt files if CCExtractor is" +
                                " enabled. If this option is disabled, only the first stream will" +
                                " be extracted (CC1)."
                );

                ccExtractorCustomOptions = new StringDeviceOption(
                        Config.getString("consumer.ffmpeg.ccextractor_custom_options", ""),
                        false,
                        "CCExtractor Custom Options",
                        "consumer.ffmpeg.ccextractor_custom_options",
                        "This allows you to add custom parameters to CCExtractor so you can" +
                                " customize the output if desired. Failure to provide valid" +
                                " options will result in CCExtractor failing to start. Be sure to" +
                                " verify that your changes work."
                );

            } catch (DeviceOptionException e) {
                logger.warn("Invalid option {}. Reverting to defaults => ", e.deviceOption, e);

                Config.setBoolean("consumer.ffmpeg.upload_id_enabled", true);
                Config.setInteger("consumer.ffmpeg.circular_buffer_size", 7864320);
                Config.setInteger("consumer.ffmpeg.min_probe_size", 165440);
                Config.setInteger("consumer.ffmpeg.min_analyze_duration", 165440);
                Config.setInteger("consumer.ffmpeg.max_analyze_duration", 5000000);
                Config.setInteger("consumer.ffmpeg.rw_buffer_size", 262144);
                Config.setInteger("consumer.ffmpeg.min_upload_id_transfer_size", 262144);
                Config.setInteger("consumer.ffmpeg.min_direct_flush_size", 1048576);
                Config.setInteger("consumer.ffmpeg.thread_priority", Thread.MAX_PRIORITY - 2);
                Config.setInteger("consumer.ffmpeg.upload_id_port", 7818);
                Config.setBoolean("consumer.ffmpeg.fix_stream", true);
                Config.setBoolean("consumer.ffmpeg.use_codec_timebase", false);
                Config.setInteger("consumer.ffmpeg.no_program_timeout_ms", 10000);
                Config.setBoolean("consumer.ffmpeg.ccextractor_enabled", false);
                Config.setBoolean("consumer.ffmpeg.ccextractor_all_streams", true);
                Config.setString("consumer.ffmpeg.ccextractor_custom_options", "");

                continue;
            }

            break;
        }
    }

    public static DeviceOption[] getFFmpegTransOptions() {
        return new DeviceOption[] {
                uploadIdEnabled,
                circularBufferSize,
                minProbeSize,
                minAnalyseDuration,
                maxAnalyseDuration,
                rwBufferSize,
                minUploadIdTransferSize,
                threadPriority,
                uploadIdPort,
                fixStream,
                useCompatibilityTimebase,
                noProgramTimeout,
                ccExtractor,
                ccExtractorAllStreams,
                ccExtractorCustomOptions
        };
    }

    public static void setOptions(JsonOption... deviceOptions) throws DeviceOptionException {
        for (JsonOption option : deviceOptions) {
            DeviceOption optionReference = FFmpegConfig.deviceOptions.get(option.getProperty());

            if (optionReference == null) {
                continue;
            }

            if (optionReference.isArray()) {
                optionReference.setValue(option.getValues());
            } else {
                optionReference.setValue(option.getValue());
            }

            Config.setDeviceOption(optionReference);
        }

        Config.saveConfig();
    }

    public static boolean getUploadIdEnabled() {
        return uploadIdEnabled.getBoolean();
    }

    public static int getCircularBufferSize() {
        return circularBufferSize.getInteger();
    }

    public static int getMinProbeSize() {
        return minProbeSize.getInteger();
    }

    public static int getMinAnalyseDuration() {
        return minAnalyseDuration.getInteger();
    }

    public static int getMaxAnalyseDuration() {
        return maxAnalyseDuration.getInteger();
    }

    public static int getRwBufferSize() {
        return rwBufferSize.getInteger();
    }

    public static int getMinUploadIdTransferSize() {
        return minUploadIdTransferSize.getInteger();
    }

    public static int getMinUploadIdTransferSize(int rwBufferSize) {
        if (rwBufferSize > minUploadIdTransferSize.getInteger()) {
            try {
                minUploadIdTransferSize.setValue(rwBufferSize);
            } catch (DeviceOptionException e) {
                logger.warn("Unable to update minUploadIdTransferSize => ", e);
            }

            return rwBufferSize;
        }

        return minUploadIdTransferSize.getInteger();
    }

    public static int getThreadPriority() {
        return threadPriority.getInteger();
    }

    public static int getUploadIdPort() {
        return uploadIdPort.getInteger();
    }

    public static boolean getFixStream() {
        return fixStream.getBoolean();
    }

    public static boolean getCcExtractor() {
        return ccExtractor.getBoolean();
    }

    public static boolean getCcExtractorAllStreams() {
        return ccExtractorAllStreams.getBoolean();
    }

    public static String getCcExtractorCustomOptions() {
        return ccExtractorCustomOptions.getValue();
    }

    public static boolean getUseCompatiblityTimebase() {
        return useCompatibilityTimebase.getBoolean();
    }

    public static int getNoProgramTimeout() {
        return noProgramTimeout.getInteger();
    }
}

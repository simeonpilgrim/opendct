/*
 * Copyright 2015-2016 The OpenDCT Authors. All Rights Reserved
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package opendct.consumer;

import opendct.config.Config;
import opendct.config.options.BooleanDeviceOption;
import opendct.config.options.DeviceOption;
import opendct.config.options.DeviceOptionException;
import opendct.config.options.IntegerDeviceOption;
import opendct.consumer.buffers.SeekableCircularBufferNIO;
import opendct.consumer.upload.NIOSageTVMediaServer;
import opendct.nanohttpd.pojo.JsonOption;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class MediaServerConsumerImpl implements SageTVConsumer {
    private static final Logger logger = LogManager.getLogger(MediaServerConsumerImpl.class);

    private final boolean preferPS = preferPSOpt.getBoolean();
    private final boolean tuningPolling = tuningPollingOpt.getBoolean();
    private final int minTransferSize = minTransferSizeOpt.getInteger();
    private final int maxTransferSize = maxTransferSizeOpt.getInteger();
    private final int bufferSize = bufferSizeOpt.getInteger();
    private final int rawThreadPriority = threadPriorityOpt.getInteger();

    // Atomic because long values take two clocks to process in 32-bit. We could get incomplete
    // values otherwise. Don't ever forget to set this value and increment it correctly. This is
    // crucial to playback actually starting in SageTV.
    private AtomicLong bytesStreamed = new AtomicLong(0);

    private AtomicBoolean running = new AtomicBoolean(false);
    private long stvRecordBufferSize = 0;

    private boolean consumeToNull = false;
    private String currentRecordingFilename = null;
    private String switchRecordingFilename = null;
    private int currentUploadID = -1;
    private int switchUploadID = -1;
    private boolean currentInit = false;
    private final Object streamingMonitor = new Object();

    private String currentRecordingQuality = null;
    private int desiredProgram = -1;
    private String tunedChannel = "";

    private volatile boolean switchFile = false;
    private final Object switchMonitor = new Object();

    private NIOSageTVMediaServer mediaServer = new NIOSageTVMediaServer();
    private ByteBuffer streamBuffer = ByteBuffer.allocateDirect(maxTransferSize);
    private SeekableCircularBufferNIO seekableBuffer = new SeekableCircularBufferNIO(bufferSize);

    private final int uploadIDPort = uploadIdPortOpt.getInteger();
    private SocketAddress uploadIDSocket = null;

    static {
        deviceOptions = new ConcurrentHashMap<>();

        initDeviceOptions();
    }

    @Override
    public void run() {
        if (running.getAndSet(true)) {
            throw new IllegalThreadStateException("MediaServer consumer is already running.");
        }

        logger.info("MediaServer thread started.");

        logger.debug("Thread priority is {}.", rawThreadPriority);
        Thread.currentThread().setPriority(rawThreadPriority);

        try {
            if (consumeToNull) {
                while (!seekableBuffer.isClosed()) {
                    streamBuffer.clear();
                    int bytesRead = seekableBuffer.read(streamBuffer);
                    bytesStreamed.addAndGet(bytesRead);
                }

                return;
            }

            boolean connected = false;
            boolean remuxStarted = false;

            // Connect and start remuxing if not buffering.
            while (!seekableBuffer.isClosed()) {
                logger.info("Opening file via MediaServer...");
                try {
                    connected = mediaServer.startUpload(
                            uploadIDSocket, currentRecordingFilename, currentUploadID);
                } catch (IOException e) {
                    logger.error("Unable to connect to socket {} => ", uploadIDSocket, e);
                }

                if (!connected) {
                    logger.error("SageTV refused the creation of the file '{}'. Trying again...",
                            currentRecordingFilename);
                    Thread.sleep(1000);

                    continue;
                }

                logger.info("Setting up remuxing on MediaServer...");
                try {
                    remuxStarted = mediaServer.setupRemux(
                            preferPS || currentRecordingFilename.endsWith(".mpg") ? "PS" : "TS",
                            true);

                } catch (IOException e) {
                    logger.error("Unable to communicate on socket {} => ", uploadIDSocket, e);
                }

                if (!remuxStarted) {
                    logger.error("SageTV refused to start remuxing the file '{}'. Trying again...",
                            currentRecordingFilename);
                    Thread.sleep(1000);

                    try {
                        mediaServer.endUpload();
                    } catch (IOException e) {
                        logger.debug("There was an exception while closing the previous connection => ", e);
                    }

                    mediaServer.reset();
                    connected = false;
                    continue;
                }

                if (stvRecordBufferSize > 0) {
                    logger.info("Setting BUFFER on MediaServer...");
                    try {
                        mediaServer.setRemuxBuffer(stvRecordBufferSize);
                    } catch (IOException e) {
                        logger.error("Unable to communicate on socket {} => ", uploadIDSocket, e);
                    }
                }

                break;
            }

            logger.info("Media Server consumer is now streaming...");

            // Start actual streaming.
            streamBuffer.clear();
            while (!seekableBuffer.isClosed()) {

                seekableBuffer.read(streamBuffer);

                if (streamBuffer.position() < minTransferSize && !seekableBuffer.isClosed()) {
                    continue;
                }

                streamBuffer.flip();

                if (switchFile) {
                    synchronized (switchMonitor) {
                        if (mediaServer.isSwitched()) {
                            currentRecordingFilename = switchRecordingFilename;
                            currentUploadID = switchUploadID;

                            switchFile = false;

                            logger.info("SWITCH successful.");
                            switchMonitor.notifyAll();
                        }
                    }
                }

                if (!currentInit) {
                    synchronized (streamingMonitor) {
                        if (tuningPolling) {
                            currentInit = mediaServer.isRemuxInitialized();

                            if (currentInit) {
                                String format = mediaServer.getFormatString();
                                logger.info("Format: {}", format);

                                streamingMonitor.notifyAll();
                            }
                        } else {
                            currentInit = true;
                            streamingMonitor.notifyAll();
                        }
                    }
                }

                int bytesToStream = streamBuffer.remaining();

                mediaServer.uploadAutoIncrement(streamBuffer);

                if (consumeToNull) {
                    bytesStreamed.addAndGet(bytesToStream);
                }

                streamBuffer.clear();
            }

        } catch (InterruptedException e) {
            logger.debug("MediaServer consumer was interrupted.");
        } catch (SocketException e) {
            logger.debug("MediaServer consumer has disconnected => ", e);
        } catch (Exception e) {
            logger.warn("MediaServer consumer created an unexpected exception => ", e);
        } finally {
            try {
                mediaServer.endUpload();
            } catch (IOException e) {
                logger.debug("There was a problem while disconnecting from MediaServer.");
            }

            logger.info("MediaServer thread stopped.");
            running.getAndSet(false);
        }
    }

    @Override
    public void write(byte[] bytes, int offset, int length) throws IOException {
        seekableBuffer.write(bytes, offset, length);
    }

    @Override
    public void write(ByteBuffer buffer) throws IOException {
        seekableBuffer.write(buffer);
    }

    @Override
    public void clearBuffer() {
        seekableBuffer.close();
        seekableBuffer.clear();
    }

    @Override
    public void setRecordBufferSize(long bufferSize) {
        stvRecordBufferSize = bufferSize;
    }

    @Override
    public boolean canSwitch() {
        return true;
    }

    @Override
    public boolean getIsRunning() {
        return running.get();
    }

    @Override
    public void stopConsumer() {
        seekableBuffer.close();
        try {
            if (mediaServer != null) {
                // If we don't do this here, it could hang things up.
                mediaServer.endUpload();
            }
        } catch (IOException e) {
            logger.debug("Error while disconnecting from Media Server => ", e);
        }
    }

    @Override
    public void consumeToNull(boolean consumeToNull) {
        this.consumeToNull = consumeToNull;
    }

    @Override
    public long getBytesStreamed() {
        if (consumeToNull) {
            return bytesStreamed.get();
        } else if (currentInit) {
            synchronized (switchMonitor) {
                try {
                    long returnValue;

                    returnValue = mediaServer.getSize();
                    bytesStreamed.set(returnValue);

                    return returnValue;
                } catch (IOException e) {
                    logger.error("Unable to get bytes from MediaServer. Replying with estimate.");
                    return bytesStreamed.get();
                }
            }
        }

        return 0;
    }

    @Override
    public boolean acceptsUploadID() {
        return true;
    }

    @Override
    public boolean acceptsFilename() {
        return false;
    }

    @Override
    public void setEncodingQuality(String encodingQuality) {
        this.currentRecordingQuality = encodingQuality;
    }

    @Override
    public boolean consumeToUploadID(String filename, int uploadId, InetAddress socketAddress) {
        logger.entry(filename, uploadId, socketAddress);

        this.currentRecordingFilename = filename;
        this.currentUploadID = uploadId;

        uploadIDSocket = new InetSocketAddress(socketAddress, uploadIDPort);

        return logger.exit(true);
    }

    @Override
    public boolean consumeToFilename(String filename) {
        // Writing to a file directly is unsupported.
        return false;
    }

    @Override
    public boolean switchStreamToUploadID(String filename, long bufferSize, int uploadId) {
        logger.entry(filename, bufferSize, uploadId);

        logger.info("SWITCH to '{}' via uploadID '{}' was requested.", filename, uploadId);

        synchronized (switchMonitor) {
            this.switchUploadID = uploadId;
            this.switchRecordingFilename = filename;

            try {
                mediaServer.switchRemux(filename, uploadId);
            } catch (IOException e) {
                logger.error("Unable to initiate SWITCH because" +
                        " of an expected communication error. => ", e);
                return false;
            }

            this.switchFile = true;

            while (switchFile && !seekableBuffer.isClosed()) {
                try {
                    switchMonitor.wait(500);
                } catch (Exception e) {
                    break;
                }
            }
        }

        return logger.exit(false);
    }

    @Override
    public boolean switchStreamToFilename(String filename, long bufferSize) {
        // Writing to a file directly is unsupported.
        return false;
    }

    @Override
    public String getEncoderQuality() {
        return currentRecordingQuality;
    }

    @Override
    public String getEncoderFilename() {
        return currentRecordingFilename;
    }

    @Override
    public int getEncoderUploadID() {
        return currentUploadID;
    }

    @Override
    public void setProgram(int program) {
        desiredProgram = program;
    }

    @Override
    public int getProgram() {
        return desiredProgram;
    }

    @Override
    public void setChannel(String channel) {
        tunedChannel = channel;
    }

    @Override
    public String getChannel() {
        return tunedChannel;
    }

    @Override
    public boolean isStreaming(long timeout) {
        if (currentInit) {
            return true;
        }

        synchronized (streamingMonitor) {
            try {
                streamingMonitor.wait(timeout);
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                logger.debug("Interrupted while waiting for consumer to start streaming.");
            }
        }

        return currentInit;
    }

    private final static Map<String, DeviceOption> deviceOptions;

    private static BooleanDeviceOption preferPSOpt;
    private static BooleanDeviceOption tuningPollingOpt;
    private static IntegerDeviceOption minTransferSizeOpt;
    private static IntegerDeviceOption maxTransferSizeOpt;
    private static IntegerDeviceOption bufferSizeOpt;
    private static IntegerDeviceOption threadPriorityOpt;
    private static IntegerDeviceOption uploadIdPortOpt;

    private static void initDeviceOptions() {
        while (true) {
            try {
                preferPSOpt = new BooleanDeviceOption(
                        Config.getBoolean("consumer.media_server.prefer_ps", true),
                        false,
                        "Prefer PS",
                        "consumer.media_server.prefer_ps",
                        "The SageTV remuxer generally works best when the output container is" +
                                " MPEG2-PS format. This will override the implicit format based" +
                                " on the extension of the filename provided."
                );

                tuningPollingOpt = new BooleanDeviceOption(
                        Config.getBoolean("consumer.media_server.tuning_polling", false),
                        false,
                        "Tuning Polling",
                        "consumer.media_server.tuning_polling",
                        "Usually OK is not replied to the SageTV server until the video is" +
                                " positively streaming. When this is disabled, the server is not" +
                                " queried to see if it is streaming which results in a nearly" +
                                " immediate reply to the server. The only down-side to this is" +
                                " the apparent responsiveness when channel surfing."
                );

                minTransferSizeOpt = new IntegerDeviceOption(
                        Config.getInteger("consumer.media_server.min_transfer_size", 64672),
                        false,
                        "Min Transfer Rate",
                        "consumer.media_server.min_transfer_size",
                        "This is the minimum number of bytes to write at one time. This value" +
                                " cannot be less than 16356 bytes and cannot be greater than" +
                                " 262072 bytes. This value will auto-align to the nearest" +
                                " multiple of 188.",
                        16356,
                        262072);

                maxTransferSizeOpt = new IntegerDeviceOption(
                        Config.getInteger("consumer.media_server.max_transfer_size", 1048476),
                        false,
                        "Max Transfer Rate",
                        "consumer.media_server.max_transfer_size",
                        "This is the maximum number of bytes to write at one time. This value" +
                                " cannot be less than 524332 bytes and cannot be greater than" +
                                " 1048476 bytes. This value will auto-align to the nearest" +
                                " multiple of 188.",
                        524332,
                        1048476);

                bufferSizeOpt = new IntegerDeviceOption(
                        Config.getInteger("consumer.media_server.stream_buffer_size", 2097152),
                        false,
                        "Stream Buffer Size",
                        "consumer.media_server.stream_buffer_size",
                        "This is the size of the streaming buffer. If this is not greater than 2" +
                                " * Max Transfer Size, it will be adjusted. This value cannot be" +
                                " less than 2097152 bytes and cannot be greater than 33554432" +
                                " bytes.",
                        2097152,
                        33554432);


                threadPriorityOpt = new IntegerDeviceOption(
                        Config.getInteger("consumer.media_server.thread_priority", Thread.MAX_PRIORITY - 2),
                        false,
                        "Raw Thread Priority",
                        "consumer.media_server.thread_priority",
                        "This is the priority given to the raw processing thread. A higher" +
                                " number means higher priority. Only change this value for" +
                                " troubleshooting. This value cannot be less than 1 and cannot be" +
                                " greater than 10.",
                        Thread.MIN_PRIORITY,
                        Thread.MAX_PRIORITY);

                uploadIdPortOpt = new IntegerDeviceOption(
                        Config.getInteger("consumer.media_server.upload_id_port", 7818),
                        false,
                        "SageTV Upload ID Port",
                        "consumer.media_server.upload_id_port",
                        "This is the port number used to communicate with SageTV when using" +
                                " upload ID for recording. You only need to change this value if" +
                                " you have changed it in SageTV. This value cannot be less than" +
                                " 1024 and cannot be greater than 65535.",
                        1024,
                        65535);

                // Enforce 188 alignment.
                minTransferSizeOpt.setValue((minTransferSizeOpt.getInteger() / 188) * 188);
                maxTransferSizeOpt.setValue((maxTransferSizeOpt.getInteger() / 188) * 188);
            } catch (DeviceOptionException e) {
                logger.warn("Invalid options. Reverting to defaults => ", e);

                Config.setBoolean("consumer.media_server.prefer_ps", true);
                Config.setBoolean("consumer.media_server.tuning_polling", false);
                Config.setInteger("consumer.media_server.min_transfer_size", 65536);
                Config.setInteger("consumer.media_server.max_transfer_size", 1048476);
                Config.setInteger("consumer.media_server.stream_buffer_size", 2097152);
                Config.setInteger("consumer.media_server.thread_priority", Thread.MAX_PRIORITY - 2);
                Config.setInteger("consumer.media_server.upload_id_port", 7818);
                continue;
            }

            break;
        }

        Config.mapDeviceOptions(
                deviceOptions,
                preferPSOpt,
                tuningPollingOpt,
                minTransferSizeOpt,
                maxTransferSizeOpt,
                bufferSizeOpt,
                threadPriorityOpt,
                uploadIdPortOpt
        );
    }

    @Override
    public DeviceOption[] getOptions() {
        return new DeviceOption[] {
                preferPSOpt,
                tuningPollingOpt,
                minTransferSizeOpt,
                maxTransferSizeOpt,
                bufferSizeOpt,
                threadPriorityOpt,
                uploadIdPortOpt
        };
    }

    @Override
    public void setOptions(JsonOption... deviceOptions) throws DeviceOptionException {
        for (JsonOption option : deviceOptions) {
            DeviceOption optionReference =
                    MediaServerConsumerImpl.deviceOptions.get(option.getProperty());

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

        // Enforce 188 alignment.
        minTransferSizeOpt.setValue((minTransferSizeOpt.getInteger() / 188) * 188);
        maxTransferSizeOpt.setValue((maxTransferSizeOpt.getInteger() / 188) * 188);

        Config.saveConfig();
    }
}

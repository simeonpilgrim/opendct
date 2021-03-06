/*
 * Copyright 2015 The OpenDCT Authors. All Rights Reserved
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

package opendct.producer;

import opendct.config.Config;
import opendct.consumer.SageTVConsumer;
import opendct.video.http.NIOHttpDownloader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.SocketException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class NIOHTTPProducerImpl implements HTTPProducer {
    private final static Logger logger = LogManager.getLogger(NIOHTTPProducerImpl.class);

    private final int httpThreadPriority =
            Math.max(
                    Math.min(
                            Config.getInteger("producer.http.nio.thread_priority", Thread.MAX_PRIORITY - 1),
                            Thread.MAX_PRIORITY
                    ),
                    Thread.MIN_PRIORITY
            );

    private AtomicBoolean running = new AtomicBoolean(false);
    private volatile boolean interrupted = false;
    private boolean stalled = false;

    private NIOHttpDownloader downloader = null;
    private URL currentURL = null;
    private URL availableURL[] = new URL[0];
    private int selectedURL = 0;
    private List<Credentials<URL>> credentials;

    private AtomicLong bytesReceived = new AtomicLong(0);

    private SageTVConsumer sageTVConsumer = null;
    private ByteBuffer localBuffer = ByteBuffer.allocateDirect(262144);

    @Override
    public synchronized void setSourceUrls(URL... urls) throws IOException {
        if (urls.length == 0) {
            throw new IOException("The connection for HTTP producer cannot process and empty array.");
        }

        selectURL(urls, false);
    }

    @Override
    public synchronized void setAuthentication(URL url, Credentials<URL> credential) {
        // This is almost copy on write, but we don't make actual copies of the objects. We just
        // create a new array and swap it out.
        List<Credentials<URL>> newList;
        if (credentials == null) {
             newList = new ArrayList<>(1);
        } else {
            newList = new ArrayList<>(credentials);
        }
        newList.add(credential);
        credentials = newList;
    }

    private void selectURL(URL urls[], boolean isThread) throws IOException {
        logger.entry(urls, isThread);

        if (!isThread) {
            if (running.get()) {
                throw new IOException("The connection for HTTP producer cannot be changed while the thread is running.");
            }
        }

        // Retry the last URL one more time before moving on to the next one.
        int lastURL = selectedURL;

        while(!isInterrupted()) {
            try {
                setSourceUrl(urls[selectedURL], isThread);

                availableURL = urls;
                currentURL = availableURL[selectedURL];

                break;
            } catch (IOException e) {
                logger.error("Unable to connect to the URL '{}' => ", urls[selectedURL], e);
            }

            if (selectedURL++ == lastURL) {
                selectedURL = 0;
                if (!isThread) {
                    throw new IOException("Unable to connect to any of the provided addresses.");
                }
            }

            if (lastURL >= urls.length) {
                lastURL = urls.length - 1;
                selectedURL = 0;
            } else if (urls.length == 1) {
                selectedURL = 0;
            } else {
                selectedURL += 1;
            }
        }
    }

    private void setSourceUrl(URL url, boolean isThread) throws IOException {
        logger.entry(url, isThread);

        if (!isThread) {
            if (running.get()) {
                throw new IOException("The connection for HTTP producer cannot be changed while the thread is running.");
            }
        }

        logger.info("Connecting to source using the URL '{}'", url);

        if (downloader != null) {
            // Make sure we are actually closing previous connections.
            downloader.close();
        }

        downloader = new NIOHttpDownloader();
        List<Credentials<URL>> localCopy = credentials;
        if (localCopy != null) {
            boolean authenticated = false;
            for (Credentials<URL> credential : localCopy)
            {
                if (credential.getKey().equals(url))
                {
                    logger.info("Connecting with credentials.");
                    downloader.connect(url, credential);
                    authenticated = true;
                    break;
                }
            }
            if (!authenticated) {
                logger.info("Connecting without credentials.");
                downloader.connect(url);
            }
        } else {
            downloader.connect(url);
        }

        currentURL = url;

        logger.exit();
    }

    public boolean getIsRunning() {
        return running.get();
    }

    public synchronized void setConsumer(SageTVConsumer sageTVConsumer) throws IOException {
        if (running.get()) {
            throw new IOException("The consumer cannot be changed while the thread is running.");
        }

        this.sageTVConsumer = sageTVConsumer;
    }

    public int getPacketsLost() {
        return 0;
    }

    public long getPackets() {
        return bytesReceived.get();
    }

    public void stopProducing() {
        interrupted = true;

        downloader.close();
    }

    private boolean isInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            interrupted = true;
        }

        return interrupted;
    }

    public void run() {
        if (running.getAndSet(true)) {
            logger.warn("The producer is already running.");
            throw new IllegalThreadStateException("The HTTP producer is already running.");
        }

        try {
            stalled = false;
            interrupted = false;

            logger.info("Producer thread is running.");

            // We could be doing channel scanning that doesn't need this kind of prioritization.
            if (Thread.currentThread().getPriority() != Thread.MIN_PRIORITY) {
                Thread.currentThread().setPriority(httpThreadPriority);
            }

            logger.debug("Thread priority is {}.", Thread.currentThread().getPriority());

            int readBytes = 0;

            // Keep re-connecting if the connection is interrupted until the producer is told to stop.
            while (!isInterrupted()) {
                while (!isInterrupted() && stalled) {
                    try {
                        downloader.connect(currentURL);
                        stalled = false;
                    } catch (IOException e) {
                        logger.error("There was a problem getting a stream => ", e);
                        stalled = true;

                        try {
                            selectURL(availableURL, true);
                            stalled = false;
                        } catch (IOException e0) {
                            stalled = true;

                            logger.warn("Unable to re-connect to any of the available addresses. Waiting 250ms before the next attempt.");

                            try {
                                Thread.sleep(250);
                            } catch (InterruptedException e1) {
                                logger.debug("Producer was interrupted waiting to retry HTTP connection => ", e.toString());
                                stalled = true;
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                    }
                }

                if (stalled) {
                    break;
                }

                while (!isInterrupted()) {
                    try {
                        if (!stalled) {
                            localBuffer.clear();
                            readBytes = downloader.read(localBuffer);
                            localBuffer.flip();

                            if (readBytes > 0) {
                                sageTVConsumer.write(localBuffer);
                                bytesReceived.addAndGet(readBytes);
                            } else {
                                logger.info("We have reached the end of the stream. Stopping thread.");
                                Thread.currentThread().interrupt();
                            }
                        }
                    } catch (IOException e) {
                        if (!(e instanceof SocketException ||
                                e instanceof AsynchronousCloseException ||
                                e.getMessage() != null && e.getMessage().equals("Stream closed"))) {

                            logger.warn("An exception occurred while receiving data => ", e);
                        } else {
                            logger.debug("The socket has been closed.");
                        }

                        stalled = true;
                        break;
                    }
                }
            }

        } catch (Exception e) {
            logger.error("Producer thread created an unexpected exception => ", e);
        } finally {
            downloader.close();
            logger.info("Producer thread has stopped.");
            running.set(false);
        }
    }

    public URL getSource() {
        return null;
    }

    public URL[] getSources() {
        return availableURL;
    }
}

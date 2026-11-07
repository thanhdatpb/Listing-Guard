package com.thanhdatpb.listingguard.mcp;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;

/**
 * Wraps the stdio input so the process can tell when the agent's host closes it: the SDK reads until the end of the
 * stream, and a stdio server has nothing left to do after that.
 */
final class EofSignalingInputStream extends FilterInputStream {

    private final CountDownLatch ended = new CountDownLatch(1);

    EofSignalingInputStream(InputStream in) {
        super(in);
    }

    @Override
    public int read() throws IOException {
        try {
            int b = super.read();
            if (b < 0) {
                ended.countDown();
            }
            return b;
        } catch (IOException e) {
            ended.countDown();
            throw e;
        }
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        try {
            int n = super.read(buffer, offset, length);
            if (n < 0) {
                ended.countDown();
            }
            return n;
        } catch (IOException e) {
            ended.countDown();
            throw e;
        }
    }

    /** Blocks until the input has reached its end or failed. */
    void awaitEnd() throws InterruptedException {
        ended.await();
    }

    @Override
    public void close() throws IOException {
        ended.countDown();
        super.close();
    }
}

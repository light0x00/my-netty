package io.github.light0x00.letty.expr.buffer;

import io.github.light0x00.letty.expr.util.Tool;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.BufferOverflowException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.nio.channels.GatheringByteChannel;
import java.nio.channels.ScatteringByteChannel;
import java.util.*;

/**
 * @author light0x00
 * @since 2023/7/5
 */
public class RingByteBuffer {

    private final ByteBuffer buffer;

    @Getter
    private int writePosition;

    @Getter
    private int readPosition;

    private int bytesUnRead;

    @Getter
    final int capacity;

    public RingByteBuffer(ByteBuffer buffer) {
        this.buffer = buffer;
        this.capacity = buffer.capacity();
    }

    /**
     * @see ByteBuffer#get()
     */
    public byte get() {
        if (bytesUnRead <= 0) {
            throw new BufferUnderflowException();
        }
        byte b = buffer.get(readPosition);
        --bytesUnRead;
        if (++readPosition == capacity) {
            readPosition = 0;
        }
        return b;
    }

    public RingByteBuffer get(byte[] dst) {
        return get(dst, 0, dst.length);
    }

    /**
     * @see ByteBuffer#get(byte[], int, int)
     */
    public RingByteBuffer get(byte[] dst, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, dst.length);

        if (length > bytesUnRead) { //要读取的字节多余现有字节
            throw new BufferUnderflowException();
        }

        for (int i = 0; i < length; i++) {
            dst[i] = buffer.get(readPosition);
            if (++readPosition == capacity) {
                readPosition = 0;
            }
        }
        bytesUnRead -= length;
        return this;
    }


    /**
     * @see ByteBuffer#put(byte)
     */
    public RingByteBuffer put(final byte value) {
        if (bytesUnRead >= capacity) {
            throw new BufferOverflowException();
        }
        buffer.put(writePosition, value);
        ++bytesUnRead;
        if (++writePosition == capacity) {
            writePosition = 0;
        }
        return this;
    }

    public RingByteBuffer put(byte[] src) {
        return put(src, 0, src.length);
    }

    /**
     * @see ByteBuffer#put(byte[], int, int)
     */
    public RingByteBuffer put(byte[] src, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, src.length);

        if (length > remainingCanPut()) { //要放入的字节多余剩余空间
            throw new BufferOverflowException();
        }
        for (int i = 0; i < length; i++) {
            buffer.put(writePosition, src[offset + i]);
            if (++writePosition == capacity) {
                writePosition = 0;
            }
        }
        bytesUnRead += length;
        return this;
    }

    /**
     * @see ByteBuffer#put(ByteBuffer)
     */
    public RingByteBuffer put(ByteBuffer src) {
        put(src, src.position(), src.remaining());
        src.position(src.position() + src.remaining());
        return this;
    }

    /**
     * @implNote The positions of the src buffer are then incremented by length.
     */
    public RingByteBuffer put(ByteBuffer src, int length) {
        put(src, src.position(), length);
        src.position(src.position() + src.remaining());
        return this;
    }

    /**
     * @throws IndexOutOfBoundsException If the preconditions on the {@code index}, {@code offset}, and
     *                                   {@code length} parameters do not hold
     * @throws ReadOnlyBufferException   If this buffer is read-only
     * @implNote The positions of the src buffer are unchanged.
     */
    public RingByteBuffer put(ByteBuffer src, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, src.limit());
        if (length > remainingCanPut()) { //要放入的字节多余剩余空间
            throw new BufferOverflowException();
        }
        if (writePosition >= readPosition) {
            int written = Math.min(capacity - writePosition, length);
            buffer.put(writePosition, src, offset, written);
            if (written < length) //右边空间不够, 则往左边写
                buffer.put(0, src, offset + written, length - written);
        } else {
            buffer.put(writePosition, src, offset, length);
        }
        moveWriteOffset(length);
        return this;
    }

    public RingByteBuffer put(RingByteBuffer src) {
        return put(src, src.remainingCanGet());
    }

    public RingByteBuffer put(RingByteBuffer src, int length) {
        if (length <= 0) {
            throw new IndexOutOfBoundsException();
        }
        if (length > src.remainingCanGet()) {
            throw new BufferUnderflowException();
        }
        if (length > remainingCanPut()) { //要放入的字节多余剩余空间
            throw new BufferOverflowException();
        }

        Fragment srcFrag = src.readableFragments().first();
        Fragment dstFrag = writableFragments().first();
        int putCnt = 0;
        for (; srcFrag != null && dstFrag != null; ) {
            int srcFragRemaining = srcFrag.length; //目的碎片 可读数量
            int dstFragRemaining = dstFrag.length; //目的碎片 可写数量

            int putLen = Math.min(Math.min(srcFragRemaining, dstFragRemaining), length - putCnt);

            put(src.buffer, src.readPosition, putLen);
            src.moveReadOffset(putLen);

            if ((putCnt += putLen) == length) {
                break;
            }

            //如果一个碎片的空间不足,则换为下一个
            dstFragRemaining -= putLen;
            srcFragRemaining -= putLen;

            if (srcFragRemaining == 0) {
                srcFrag = srcFrag.next;
            }
            if (dstFragRemaining == 0) {
                dstFrag = dstFrag.next;
            }
        }
        return this;
    }

    /**
     * 写入 channel 数据到 buffer
     */
    public int readFromChannel(ScatteringByteChannel channel) throws IOException {
        FragmentList list = writableFragments();
        if (list.size > 0) {
            ByteBuffer[] buffers = Arrays.stream(list.toArray())
                    .map(Fragment::sliceBuf)
                    .toArray(ByteBuffer[]::new);
            //Impossible to overflow the range of int,
            //cuz to the buffers are fragments from only one ByteBuffer, which is at most Integer.MAX_VALUE bytes
            int n = (int) channel.read(buffers);
            moveWriteOffset(n);
            return n;
        }
        return 0;
    }

    /**
     * 读出 buffer 数据到 channel
     */
    public int writeToChannel(GatheringByteChannel channel) throws IOException {
        FragmentList list = readableFragments();
        if (list.size > 0) {
            ByteBuffer[] buffers = Arrays.stream(list.toArray())
                    .map(Fragment::sliceBuf)
                    .toArray(ByteBuffer[]::new);
            int n = (int) channel.write(buffers); //不可能超过 int 范围
            moveReadOffset(n);
            return n;
        }
        return 0;
    }

    private FragmentList readableFragments() {
        FragmentList fragments = new FragmentList();
        // r    w
        /*
            * r * w
            0 1 2 3
         */
        if (writePosition > readPosition) {
            fragments.offer(new Fragment(readPosition, writePosition - readPosition));
        }
        /*
            * w * r
            0 1 2 3
        */
        else if (writePosition < readPosition) {
            fragments.offer(new Fragment(readPosition, capacity - readPosition));
            if (writePosition > 0) {
                fragments.offer(new Fragment(0, writePosition));
            }
        }
        /*
            * w/r * *
            0  1  2 3
        */
        else {
            if (bytesUnRead == capacity) { // 写指针追上读指针, 则存在可读数据, 反之则没有可读数据
                fragments.offer(new Fragment(writePosition, capacity - writePosition));
                if (writePosition > 0) {
                    fragments.offer(new Fragment(0, writePosition));
                }
            }
        }
        return fragments;
    }

    private FragmentList writableFragments() {

        FragmentList fragments = new FragmentList();
        /*
            * r * w
            0 1 2 3
         */
        if (writePosition > readPosition) {
            fragments.offer(new Fragment(writePosition, capacity - writePosition));
            if (readPosition > 0) {
                fragments.offer(new Fragment(0, readPosition));
            }
        }
        /*
            * w * r
            0 1 2 3
        */
        else if (writePosition < readPosition) {
            fragments.offer(new Fragment(writePosition, readPosition - writePosition));
        }
        /*
            * w/r * *
            0  1  2 3
        */
        else {
            if (bytesUnRead == 0) {   // 读指针追上写指针, 则存在可写空间 , 反之则没有剩余可写空间
                fragments.offer(new Fragment(writePosition, capacity - writePosition));
                if (writePosition > 0) {
                    fragments.offer(new Fragment(0, writePosition));
                }
            }
        }
        return fragments;
    }

    public int getInt() {
        byte[] bytes = new byte[4];
        get(bytes);
        return Tool.bytesToInt(bytes);
    }

    public RingByteBuffer putInt(int value) {
        return put(Tool.intToBytes(value));
    }

    public int remainingCanGet() {
        return bytesUnRead;
    }

    public int remainingCanPut() {
        return capacity - bytesUnRead;
    }

    public void clear() {
        readPosition = 0;
        writePosition = 0;
        bytesUnRead = 0;
    }

    private void moveReadOffset(int step) {
        if (remainingCanGet() < step) {
            throw new BufferUnderflowException();
        }
        readPosition = (readPosition + step) % capacity;
        bytesUnRead -= step;
    }

    private void moveWriteOffset(int step) {
        if (remainingCanPut() < step) {
            throw new BufferOverflowException();
        }
        writePosition = (writePosition + step) % capacity;
        bytesUnRead += step;
    }

    /**
     * FIFO queue
     */
    class FragmentList implements Iterable<Fragment> {
        @Getter
        private int size;
        private final Fragment HEAD = new Fragment();
        private Fragment cur = HEAD;

        public void offer(Fragment fragment) {
            cur.next = fragment;
            cur = fragment;
            size++;
        }

        @Nullable
        public Fragment poll() {
            var p = HEAD.next;
            if (p != null) {
                HEAD.next = p.next;
            }
            return p;
        }

        @Nullable
        public Fragment first() {
            return HEAD.next;
        }

        public boolean isEmpty() {
            return size == 0;
        }

        public Fragment[] toArray() {
            Fragment[] arr = new Fragment[size];
            int i = 0;
            for (Fragment fragment : this) {
                arr[i++] = fragment;
            }
            return arr;
        }

        @Nonnull
        @Override
        public Iterator<Fragment> iterator() {
            return new Iterator<>() {
                Fragment cur = HEAD;

                @Override
                public boolean hasNext() {
                    return cur.next != null;
                }

                @Override
                public Fragment next() {
                    cur = cur.next;
                    return cur;
                }
            };
        }
    }

    class Fragment {

        @Getter
        private int offset;

        @Getter
        private int length;

        RingByteBuffer ring;

        Fragment next;

        Fragment() {
            //only for pseudo node usage
        }

        Fragment(int offset, int length) {
            this.offset = offset;
            this.length = length;
            this.ring = RingByteBuffer.this;
        }

        /**
         * 碎片对应的 buffer 切片
         */
        ByteBuffer sliceBuf(){
            return buffer.slice(offset, length);
        }
    }

}

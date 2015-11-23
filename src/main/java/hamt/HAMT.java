package hamt;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;


/**
 *  Hash array mapped trie implementation in Java
 *
 *  <Header><Data>
 *
 *  Header:
 *
 *  |3b-|b|2b|2b|3b-|-5b--|
 *    |  |  |  |  |   |
 *    |  |  |  |  |   Number of levels (n)
 *    |  |  |  |  |
 *    |  |  |  |  Bitmask size in bytes (2^n)
 *    |  |  |  |
 *    |  |  |  Pointer size in bytes (n+1)
 *    |  |  |
 *    |  |  Value size (2^n)
 *    |  Variable value size flag (not implemented yet)
 *    |
 *     Reserved
 *
 *  Data:
 *
 *  [<Bitmask><LayerData>]
 */
public class HAMT {
    private static final int NUM_LEVELS_OFFSET = 0;
    private static final int BITMASK_SIZE_OFFSET = 5;
    private static final int PTR_SIZE_OFFSET = 8;
    private static final int VALUE_SIZE_OFFSET = 10;
    private static final int VARIABLE_VALUE_SIZE_OFFSET = 12;
    private static final int LEVELS_MASK = 0b0001_1111;
    private static final int BITMASK_SIZE_MASK = 0b0000_0111;
    private static final int PTR_SIZE_MASK = 0b0000_0011;
    private static final int VALUE_SIZE_MASK = 0b0000_0011;

    public static enum BitmaskSize {
        BYTE(1), SHORT(2), INT(4), LONG(8);

        private static final Map<Integer,BitmaskSize> sizesMap = new HashMap<>();
        static {
            for (BitmaskSize bitmaskSize : values()) {
                sizesMap.put(bitmaskSize.size, bitmaskSize);
            }
        }

        public final int size;
        public final int shiftBits;
        public final int shiftMask;

        BitmaskSize(int size) {
            this.size = size;
            this.shiftBits = 31 - Integer.numberOfLeadingZeros(this.size << 3);
            this.shiftMask = (1 << this.shiftBits) - 1;
        }

        public int encode() {
            return this.shiftBits - 3;
        }

        public static BitmaskSize get(int size) {
            return sizesMap.get(size);
        }

        public static BitmaskSize decode(int value) {
            return BitmaskSize.get(1 << value);
        }
    }
    
    public static enum ValueSize {
        BYTE(1), SHORT(2), INT(4), LONG(8), VAR(-1);

        private static final Map<Integer,ValueSize> sizesMap = new HashMap<>();
        static {
            for (ValueSize valueSize : values()) {
                sizesMap.put(valueSize.size, valueSize);
            }
        }

        public final int size;
        public final int shiftBits;

        ValueSize(int size) {
            this.size = size;
            this.shiftBits = 31 - Integer.numberOfLeadingZeros(this.size << 3);
        }

        public int encode() {
            return this.shiftBits - 3;
        }

        public static ValueSize get(int size) {
            return sizesMap.get(size);
        }

        public static ValueSize decode(int value) {
            return ValueSize.get(1 << value);
        }
    }
    
    public static class Writer {
        private final BitmaskSize bitmaskSize;
        private final ValueSize valueSize;

        public Writer(BitmaskSize bitmaskSize, ValueSize valueSize) {
            this.bitmaskSize = bitmaskSize;
            this.valueSize = valueSize;
        }

        private Writer(int bitmaskSize, int valueSize) {
            this(BitmaskSize.get(bitmaskSize), ValueSize.get(valueSize));
        }

        public ValueSize valueSize() {
            return valueSize;
        }

        int getLevels(long maxKey) {
            int levels = 1;
            long key = maxKey >>> this.bitmaskSize.shiftBits;
            while (key != 0) {
                levels++;
                key = key >>> this.bitmaskSize.shiftBits;
            }
            return levels;
        }

        int getPtrSize(List<LayerData> layers) {
            int ptrSize = 0;
            for (int ps = 0; ps <= 3; ps++) {
                ptrSize = ps + 1;
                int maxSize = 1 << (8 * ptrSize);
                int size = 0;
                for (LayerData l : layers) {
                    size += l.size(ptrSize, this.valueSize.size);
                    if (size > maxSize) {
                        break;
                    }
                }
                if (size > maxSize) {
                    continue;
                } else {
                    break;
                }
            }
            return ptrSize;
        }

        short getHeader(int numLevels, int ptrSize) {
            assert 1 <= ptrSize && ptrSize <= 4;

            int header = 0;
            header |= numLevels << NUM_LEVELS_OFFSET;
            header |= this.bitmaskSize.encode() << BITMASK_SIZE_OFFSET;
            header |= (ptrSize - 1) << PTR_SIZE_OFFSET;
            header |= this.valueSize.encode() << VALUE_SIZE_OFFSET;
            return (short) header;
        }

        public byte[] dumpBytes(List<Long> keys, List<Byte> values) {
            return dumpBytes(Utils.toLongArray(keys), Utils.toByteArray(values));
        }

        public byte[] dumpBytes(long[] keys, byte[] values) {
            assert valueSize == ValueSize.BYTE;
            byte[][] bytesArray = new byte[values.length][];
            for (int i = 0; i < values.length; i++) {
                bytesArray[i] = new byte[]{ values[i] };
            }
            return dump(keys, bytesArray);
        }

        // public byte[] dumpShorts(List<Long> keys, List<Short> values) {
        //     assert valueSize == ValueSize.SHORT;
        //     return dump(keys,
        //                 new ArrayList<byte[]>(values.size()) {{ for (short v : values) { add(Utils.shortToBytesLE(v)); } }});
        // }

        // public byte[] dumpInts(List<Long> keys, List<Integer> values) {
        //     assert valueSize == ValueSize.INT;
        //     return dump(keys,
        //                 new ArrayList<byte[]>(values.size()) {{ for (int v : values) { add(Utils.intToBytesLE(v)); } }});
        // }

        // public byte[] dumpLongs(List<Long> keys, List<Long> values) {
        //     assert valueSize == ValueSize.LONG;
        //     return dump(keys,
        //                 new ArrayList<byte[]>(values.size()) {{ for (long v : values) { add(Utils.longToBytesLE(v)); } }});
        // }

        public byte[] dumpFloats(List<Long> keys, List<Float> values) {
            return dumpFloats(Utils.toLongArray(keys), Utils.toFloatArray(values));
        }

        public byte[] dumpFloats(long[] keys, float[] values) {
            assert valueSize == ValueSize.INT;
            byte[][] bytesArray = new byte[values.length][];
            for (int i = 0; i < values.length; i++) {
                bytesArray[i] = Utils.floatToBytesLE(values[i]);
            }
            return dump(keys, bytesArray);
        }

        // public byte[] dumpDoubles(List<Long> keys, List<Double> values) {
        //     assert valueSize == ValueSize.LONG;
        //     return dump(keys,
        //                 new ArrayList<byte[]>(values.size()) {{ for (double v : values) { add(Utils.doubleToBytesLE(v)); } }});
        // }

        public byte[] dump(List<Long> keys, List<byte[]> values) {
            return dump(Utils.toLongArray(keys), Utils.toBytesArray(values));
        }

        public byte[] dump(long[] keys, byte[][] values) {
            assert keys.length == values.length;

            if (keys.length == 0) {
                return new byte[0];
            }

            long maxKey = keys[keys.length - 1];
            int numLevels = getLevels(maxKey);
            List<LayerData> layers = new ArrayList<>();
            layers.add(new LayerData(this.bitmaskSize.size));
            Map<Long,LayerData> layersMap = new HashMap<>();
            for (long key : keys) {
                layersMap.put(key, layers.get(0));
            }
            for (int l = numLevels; l > 0; l--) {
                LayerData prevSubLayer = null;
                int i = 0;
                for (long key : keys) {
                    int k = (int) (key >>> ((l - 1) * this.bitmaskSize.shiftBits) & this.bitmaskSize.shiftMask);
                    LayerData layer = layersMap.get(key);
                    if (l == 1) {
                        layer.addValue(values[i]);
                    } else {
                        LayerData subLayer = layer.newLayer(k);
                        if (subLayer != prevSubLayer) {
                            layers.add(subLayer);
                        }
                        prevSubLayer = subLayer;
                        layersMap.put(key, subLayer);
                    }
                    layer.setBit(k);
                    i++;
                }
            }
            int ptrSize = getPtrSize(layers);

            int bufferSize = 2;
            for (LayerData layer : layers) {
                int layerSize = layer.size(ptrSize, valueSize.size);
                layer.setOffset(bufferSize - 2);
                bufferSize += layerSize;
            }
            ByteBuffer buffer = ByteBuffer.allocate(bufferSize);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            buffer.putShort(getHeader(numLevels, ptrSize));
            for (LayerData layer : layers) {
                layer.dump(buffer, ptrSize);
            }
            return buffer.array();
        }

        class LayerData {
            public byte[] bitmask;
            public int offset;
            public List<LayerData> layers = new ArrayList<>();
            public List<byte[]> values = new ArrayList<>();

            LayerData(int bitmaskSize) {
                this.bitmask = new byte[bitmaskSize];
            }
        
            void setBit(int k) {
                int n = k >>> 3;
                int b = k & 0b0000_0111;
                this.bitmask[n] = (byte) (this.bitmask[n] | (1 << b));
            }

            LayerData newLayer(int k) {
                int n = k >>> 3;
                int b = k & 0b0000_0111;
                if ((this.bitmask[n] & (1 << b)) != 0) {
                    return this.layers.get(this.layers.size() - 1);
                }
                else {
                    LayerData l = new LayerData(this.bitmask.length);
                    this.layers.add(l);
                    return l;
                }
            }

            void addValue(byte[] v) {
                this.values.add(v);
            }

            void setOffset(int o) {
                this.offset = o;
            }

            int size(int ptrSize, int valueSize) {
                return bitmask.length + layers.size() * ptrSize + values.size() * valueSize;
            }
        
            void dump(ByteBuffer buffer, int ptrSize) {
                buffer.put(this.bitmask);
                if (!this.layers.isEmpty()) {
                    for (LayerData l : this.layers) {
                        buffer.put(POINTER_DECODERS[ptrSize - 1].encode(l.offset));
                    }
                } else {
                    for (byte[] v : this.values) {
                        buffer.put(v);
                    }
                }
            }
        }
    }

    public static class Reader {
        private final int numLevels;
        private final BitmaskSize bitmaskSize;
        private final int ptrSize;
        private final ValueSize valueSize;
        private final ByteBuffer buffer;

        public Reader(byte[] data) {
            ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            short header = buffer.getShort();
            this.numLevels = ((header >>> NUM_LEVELS_OFFSET) & LEVELS_MASK);
            this.bitmaskSize = BitmaskSize.decode((header >>> BITMASK_SIZE_OFFSET) & BITMASK_SIZE_MASK);
            this.ptrSize = ((header >>> PTR_SIZE_OFFSET) & PTR_SIZE_MASK) + 1;
            this.valueSize = ValueSize.decode((header >>> VALUE_SIZE_OFFSET) & VALUE_SIZE_MASK);
            this.buffer = buffer.slice();
        }

        public int numLevels() {
            return numLevels;
        }

        public BitmaskSize bitmaskSize() {
            return bitmaskSize;
        }

        public int ptrSize() {
            return ptrSize;
        }

        public ValueSize valueSize() {
            return valueSize;
        }

        int getValueOffset(long key) {
            this.buffer.position(0);

            if (
                this.numLevels * this.bitmaskSize.shiftBits < 64
                && key >>> (this.numLevels * this.bitmaskSize.shiftBits) > 0
            ) {
                return -1;
            }

            int layerOffset = 0;
            int ptrOffset = 0;
            byte[] bitmask = new byte[this.bitmaskSize.size];
            byte[] ptrBytes = new byte[this.ptrSize];
            PointerDecoder ptrDecoder = POINTER_DECODERS[this.ptrSize - 1];
            for (int level = numLevels - 1; level >= 0; level--) {
                long k = key >>> (level * this.bitmaskSize.shiftBits) & this.bitmaskSize.shiftMask;
                int nByte = (int) (k >>> 3);
                int nBit = (int) (k & 0b0000_0111);
                this.buffer.position(layerOffset);
                this.buffer.get(bitmask);
                if ((bitmask[nByte] & (1 << nBit)) == 0) {
                    return -1;
                }
                ptrOffset = BIT_COUNTERS[nByte].count(bitmask, nByte, nBit);
                if (level != 0) {
                    this.buffer.position(layerOffset + bitmask.length + ptrOffset * this.ptrSize);
                    this.buffer.get(ptrBytes);
                    layerOffset = ptrDecoder.decode(ptrBytes);
                }
            }
            return layerOffset + bitmask.length + ptrOffset * this.valueSize.size;
        }

        public boolean exists(long key) {
            int valueOffset = getValueOffset(key);
            return valueOffset > 0 ? true : false;
        }

        public byte[] get(long key, byte[] defaultValue) {
            int valueOffset = getValueOffset(key);
            if (valueOffset > 0) {
                byte[] value = new byte[this.valueSize.size];
                this.buffer.position(valueOffset);
                this.buffer.get(value);
                return value;
            }
            return defaultValue;
        }

        private static final BitCounter DEFAULT_BIT_COUNTER = new BitCounter();
        private static final BitCounter[] BIT_COUNTERS = new BitCounter[] {
            new BitCounter() {
                @Override
                int count(byte[] bytes, int nByte, int nBit) {
                    return BIT_COUNTS[bytes[0] & BIT_COUNT_MASKS[nBit]];
                }
            },
            new BitCounter() {
                @Override
                int count(byte[] bytes, int nByte, int nBit) {
                    return BIT_COUNTS[bytes[0] & 0xff] + BIT_COUNTS[bytes[1] & BIT_COUNT_MASKS[nBit]];
                }
            },
            new BitCounter() {
                @Override
                int count(byte[] bytes, int nByte, int nBit) {
                    return BIT_COUNTS[bytes[0] & 0xff] + BIT_COUNTS[bytes[1] & 0xff] + BIT_COUNTS[bytes[2] & BIT_COUNT_MASKS[nBit]];
                }
            },
            new BitCounter() {
                @Override
                int count(byte[] bytes, int nByte, int nBit) {
                    return BIT_COUNTS[bytes[0] & 0xff] + BIT_COUNTS[bytes[1] & 0xff] + BIT_COUNTS[bytes[2] & 0xff] + BIT_COUNTS[bytes[3] & BIT_COUNT_MASKS[nBit]];
                }
            },
            DEFAULT_BIT_COUNTER,
            DEFAULT_BIT_COUNTER,
            DEFAULT_BIT_COUNTER,
            DEFAULT_BIT_COUNTER,
        };

        static class BitCounter {
            protected static byte[] BIT_COUNT_MASKS = new byte[]{
                0b0000_0000,
                0b0000_0001,
                0b0000_0011,
                0b0000_0111,
                0b0000_1111,
                0b0001_1111,
                0b0011_1111,
                0b0111_1111
            };
            protected static byte[] BIT_COUNTS = new byte[256];
            static {
                for (int i = 0; i <= 255; i++) {
                    BIT_COUNTS[i] = (byte) Integer.bitCount(i);
                }
            }

            int count(byte[] bytes, int nByte, int nBit) {
                int count = BIT_COUNTS[bytes[nByte] & BIT_COUNT_MASKS[nBit]];
                for (int byteIx = nByte - 1; byteIx >= 0; byteIx--) {
                    int b = bytes[byteIx] & 0xff;
                    count += BIT_COUNTS[b];
                }
                return count;
            }
        }

    }

    private static final PointerDecoder[] POINTER_DECODERS = new PointerDecoder[] {
        new PointerDecoder() {
            @Override
            public byte[] encode(int ptr) {
                return new byte[]{ (byte) (ptr & 0xff) };
            }

            @Override
            public int decode(byte[] array) {
                return array[0] & 0xff;
            }
        },
        new PointerDecoder() {
            @Override
            public byte[] encode(int ptr) {
                return new byte[]{ (byte) (ptr & 0xff),
                                   (byte) ((ptr >>> 8) & 0xff) };
            }

            @Override
            public int decode(byte[] array) {
                return (array[0] & 0xff) | ((array[1] & 0xff) << 8);
            }
        },
        new PointerDecoder() {
            @Override
            public byte[] encode(int ptr) {
                return new byte[]{ (byte) (ptr & 0xff),
                                   (byte) ((ptr >>> 8) & 0xff),
                                   (byte) ((ptr >>> 16) & 0xff) };
            }

            @Override
            public int decode(byte[] array) {
                return (array[0] & 0xff) | ((array[1] & 0xff) << 8) | ((array[2] & 0xff) << 16);
            }
        },
        new PointerDecoder() {
            @Override
            public byte[] encode(int ptr) {
                return new byte[]{ (byte) (ptr & 0xff),
                                   (byte) ((ptr >>> 8) & 0xff),
                                   (byte) ((ptr >>> 16) & 0xff),
                                   (byte) ((ptr >>> 24) & 0xff) };
            }

            @Override
            public int decode(byte[] array) {
                return (array[0] & 0xff) | ((array[1] & 0xff) << 8) | ((array[2] & 0xff) << 16) | ((array[3] & 0xff) << 24);
            }
        }
    };
        
    interface PointerDecoder {
        byte[] encode(int ptr);
            
        int decode(byte[] array);
    }

    public static class Utils {
        public static byte[] shortToBytesLE(short v) {
            return new byte[]{ (byte) (v & 0xff),
                               (byte) ((v >>> 8) & 0xff) };
        }

        public static byte[] intToBytesLE(int v) {
            return new byte[]{ (byte) (v & 0xff),
                               (byte) ((v >>> 8) & 0xff),
                               (byte) ((v >>> 16) & 0xff),
                               (byte) ((v >>> 24) & 0xff) };
        }

        public static byte[] longToBytesLE(long v) {
            return new byte[]{ (byte) (v & 0xff),
                               (byte) ((v >>> 8) & 0xff),
                               (byte) ((v >>> 16) & 0xff),
                               (byte) ((v >>> 24) & 0xff),
                               (byte) ((v >>> 32) & 0xff),
                               (byte) ((v >>> 40) & 0xff),
                               (byte) ((v >>> 48) & 0xff),
                               (byte) ((v >>> 56) & 0xff) };
        }

        public static byte[] floatToBytesLE(float v) {
            return intToBytesLE(Float.floatToIntBits(v));
        }

        public static byte[] doubleToBytesLE(double v) {
            return longToBytesLE(Double.doubleToLongBits(v));
        }

        public static byte[] toByteArray(List<Byte> values) {
            byte[] array = new byte[values.size()];
            for (int i = 0; i < array.length; i++) {
                array[i] = values.get(i);
            }
            return array;
        }

        public static byte[][] toBytesArray(List<byte[]> values) {
            byte[][] array = new byte[values.size()][];
            for (int i = 0; i < array.length; i++) {
                array[i] = values.get(i);
            }
            return array;
        }

        public static short[] toShortArray(List<Short> values) {
            short[] array = new short[values.size()];
            for (int i = 0; i < array.length; i++) {
                array[i] = values.get(i);
            }
            return array;
        }

        public static int[] toIntArray(List<Integer> values) {
            int[] array = new int[values.size()];
            for (int i = 0; i < array.length; i++) {
                array[i] = values.get(i);
            }
            return array;
        }

        public static long[] toLongArray(List<Long> values) {
            long[] array = new long[values.size()];
            for (int i = 0; i < array.length; i++) {
                array[i] = values.get(i);
            }
            return array;
        }

        public static float[] toFloatArray(List<Float> values) {
            float[] array = new float[values.size()];
            for (int i = 0; i < array.length; i++) {
                array[i] = values.get(i);
            }
            return array;
        }

        public static double[] toDoubleArray(List<Double> values) {
            double[] array = new double[values.size()];
            for (int i = 0; i < array.length; i++) {
                array[i] = values.get(i);
            }
            return array;
        }
    }
}

package dev.kurekame.client.data.serialisation;

import dev.kurekame.client.data.BlockGrid;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class RegionSerialiser implements AutoCloseable {
    private static final int REGION_SIZE_XZ = 32;
    private static final int MAX_SECTIONS_Y = 32;
    private static final int CHUNKS_PER_REGION = REGION_SIZE_XZ * REGION_SIZE_XZ * MAX_SECTIONS_Y;

    private static final int GRID_INT_COUNT = 16 * 16 * 16;
    private static final int GRID_BYTE_SIZE = GRID_INT_COUNT * Integer.BYTES;

    private static final int HEADER_OFFSET_TABLE_SIZE = CHUNKS_PER_REGION * Integer.BYTES;
    private static final int HEADER_LENGTH_TABLE_SIZE = CHUNKS_PER_REGION * Integer.BYTES;
    private static final int HEADER_SIZE = HEADER_OFFSET_TABLE_SIZE + HEADER_LENGTH_TABLE_SIZE;

    private final Path baseFolder;
    private final Map<RegionKey, RegionFile> openRegions = new ConcurrentHashMap<>();

    public RegionSerialiser(Path baseFolder) throws IOException {
        this.baseFolder = baseFolder;
        if (!Files.exists(baseFolder)) {
            Files.createDirectories(baseFolder);
        }
    }

    public synchronized void writeGrid(int chunkX, int sectionY, int chunkZ, BlockGrid grid) throws IOException {
        RegionFile region = getOrCreateRegion(chunkX, chunkZ);
        int localIndex = get3DLocalIndex(chunkX, sectionY, chunkZ);
        region.writeChunk(localIndex, grid.getBlocks());
    }

    public synchronized BlockGrid readGrid(int chunkX, int sectionY, int chunkZ) throws IOException {
        RegionFile region = getOrCreateRegion(chunkX, chunkZ);
        int localIndex = get3DLocalIndex(chunkX, sectionY, chunkZ);
        int[] blocks = region.readChunk(localIndex);
        return blocks != null ? new BlockGrid(blocks) : null;
    }

    private RegionFile getOrCreateRegion(int chunkX, int chunkZ) throws IOException {
        int regX = Math.floorDiv(chunkX, REGION_SIZE_XZ);
        int regZ = Math.floorDiv(chunkZ, REGION_SIZE_XZ);

        RegionKey key = new RegionKey(regX, regZ);

        RegionFile region = openRegions.get(key);
        if (region == null) {
            String fileName = String.format("r.%d.%d.region", regX, regZ);
            Path regionPath = baseFolder.resolve(fileName);
            region = new RegionFile(regionPath);
            openRegions.put(key, region);
        }
        return region;
    }

    private int get3DLocalIndex(int chunkX, int sectionY, int chunkZ) {
        int relX = Math.floorMod(chunkX, REGION_SIZE_XZ);
        int relZ = Math.floorMod(chunkZ, REGION_SIZE_XZ);

        int relY = sectionY + 16;
        if (relY < 0 || relY >= MAX_SECTIONS_Y) {
            return 0;
        }

        return relX + REGION_SIZE_XZ * (relZ + REGION_SIZE_XZ * relY);
    }

    @Override
    public synchronized void close() throws IOException {
        for (RegionFile region : openRegions.values()) {
            region.close();
        }
        openRegions.clear();
    }

    private record RegionKey(int x, int z) {}

    private static class RegionFile implements AutoCloseable {

        private final RandomAccessFile file;
        private final FileChannel channel;
        private final int[] offsets = new int[CHUNKS_PER_REGION];
        private final int[] lengths = new int[CHUNKS_PER_REGION];

        public RegionFile(Path path) throws IOException {
            this.file = new RandomAccessFile(path.toFile(), "rw");
            this.channel = file.getChannel();

            if (file.length() < HEADER_SIZE) {
                file.setLength(HEADER_SIZE);
            } else {
                ByteBuffer headerBuffer = ByteBuffer.allocate(HEADER_SIZE);
                channel.read(headerBuffer, 0);
                headerBuffer.flip();

                IntBuffer intBuffer = headerBuffer.asIntBuffer();
                intBuffer.get(offsets);
                intBuffer.get(lengths);
            }
        }

        public synchronized void writeChunk(int localIndex, int[] blocks) throws IOException {
            int offset = offsets[localIndex];

            if (offset == 0) {
                offset = (int) channel.size();
                offsets[localIndex] = offset;
                lengths[localIndex] = GRID_BYTE_SIZE;

                updateHeaderEntry(localIndex, offset, GRID_BYTE_SIZE);
            }

            ByteBuffer buffer = ByteBuffer.allocate(GRID_BYTE_SIZE);
            IntBuffer intBuffer = buffer.asIntBuffer();
            intBuffer.put(blocks);

            channel.write(buffer, offset);
        }

        public synchronized int[] readChunk(int localIndex) throws IOException {
            int offset = offsets[localIndex];
            int length = lengths[localIndex];

            if (offset == 0 || length == 0) {
                return null;
            }

            ByteBuffer buffer = ByteBuffer.allocate(GRID_BYTE_SIZE);
            channel.read(buffer, offset);
            buffer.flip();

            int[] blocks = new int[GRID_INT_COUNT];
            buffer.asIntBuffer().get(blocks);
            return blocks;
        }

        private void updateHeaderEntry(int localIndex, int offset, int length) throws IOException {
            ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES);

            buffer.putInt(offset);
            buffer.flip();
            long offsetPos = (long) localIndex * Integer.BYTES;
            channel.write(buffer, offsetPos);

            buffer.clear();
            buffer.putInt(length);
            buffer.flip();
            long lengthPos = HEADER_OFFSET_TABLE_SIZE + ((long) localIndex * Integer.BYTES);
            channel.write(buffer, lengthPos);
        }

        @Override
        public synchronized void close() throws IOException {
            if (channel.isOpen()) {
                channel.close();
            }
            file.close();
        }
    }
}
package net.lovelace.vesuvio.engine.physics;

/** Immutable packet block IDs. A missing section is unknown; a uniform section uses one int. */
public final class ClientChunk {
    private final int minSection;
    private final int[][] sections;
    private final long bytes;

    public ClientChunk(int minSection, int[][] sections) {
        if (sections.length == 0 || sections.length > 64) throw new IllegalArgumentException("Некорректное число секций");
        this.minSection = minSection;
        this.sections = new int[sections.length][];
        long size = 0;
        for (int i = 0; i < sections.length; i++) {
            if (sections[i] == null) continue;
            if (sections[i].length != 1 && sections[i].length != 4096) throw new IllegalArgumentException("Некорректная секция");
            this.sections[i] = sections[i].clone();
            size += (long) sections[i].length * Integer.BYTES;
        }
        bytes = size;
    }

    private ClientChunk(int minSection, int[][] owned, long bytes) {
        this.minSection = minSection; this.sections = owned; this.bytes = bytes;
    }
    public int minSection() { return minSection; }
    public int sectionCount() { return sections.length; }
    public long bytes() { return bytes; }
    public int block(int x, int y, int z) {
        int section = (y >> 4) - minSection;
        if (section < 0 || section >= sections.length || sections[section] == null) return -1;
        int[] values = sections[section];
        return values.length == 1 ? values[0] : values[index(x, y, z)];
    }
    public ClientChunk withBlocks(java.util.List<ClientWorld.BlockEdit> edits) {
        int[][] changed = sections.clone();
        boolean[] copied = new boolean[sections.length];
        long size = bytes;
        for (var edit : edits) {
            int section = (edit.y() >> 4) - minSection;
            if (section < 0 || section >= sections.length || changed[section] == null) continue;
            if (!copied[section]) {
                int[] previous = changed[section];
                changed[section] = new int[4096];
                if (previous.length == 1) java.util.Arrays.fill(changed[section], previous[0]);
                else System.arraycopy(previous, 0, changed[section], 0, 4096);
                size += (4096L - previous.length) * Integer.BYTES;
                copied[section] = true;
            }
            changed[section][index(edit.x(), edit.y(), edit.z())] = edit.stateId();
        }
        return new ClientChunk(minSection, changed, size);
    }
    private static int index(int x, int y, int z) { return ((y & 15) << 8) | ((z & 15) << 4) | (x & 15); }
}

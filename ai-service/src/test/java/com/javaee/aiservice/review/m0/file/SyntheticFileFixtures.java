package com.javaee.aiservice.review.m0.file;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class SyntheticFileFixtures {
    private SyntheticFileFixtures() {
    }

    public static byte[] pdf(String marker) {
        return ("%PDF-1.4\n1 0 obj<</Type/Catalog>>endobj\n%" + marker + "\n%%EOF")
                .getBytes(StandardCharsets.ISO_8859_1);
    }

    public static byte[] encryptedPdf() {
        return "%PDF-1.4\n1 0 obj<</Encrypt 2 0 R>>endobj\n%%EOF"
                .getBytes(StandardCharsets.ISO_8859_1);
    }

    public static byte[] jpg(byte marker) {
        return new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, marker, 1, 2, (byte) 0xff, (byte) 0xd9};
    }

    public static byte[] png(byte marker) {
        return new byte[]{
                (byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a,
                marker, 0, 0, 0, 0, 'I', 'E', 'N', 'D', 0, 0, 0, 0
        };
    }

    public static byte[] docx(String marker) {
        return zip(List.of(
                entry("[Content_Types].xml", ("<Types>" + marker + "</Types>").getBytes(StandardCharsets.UTF_8)),
                entry("word/document.xml", ("<document>" + marker + "</document>")
                        .getBytes(StandardCharsets.UTF_8))));
    }

    public static byte[] xlsx(String marker) {
        return zip(List.of(
                entry("[Content_Types].xml", ("<Types>" + marker + "</Types>").getBytes(StandardCharsets.UTF_8)),
                entry("xl/workbook.xml", ("<workbook>" + marker + "</workbook>")
                        .getBytes(StandardCharsets.UTF_8))));
    }

    public static ZipItem entry(String name, byte[] bytes) {
        return new ZipItem(name, bytes, false, ZipEntry.DEFLATED);
    }

    public static ZipItem directory(String name) {
        return new ZipItem(name.endsWith("/") ? name : name + "/", new byte[0], true, ZipEntry.STORED);
    }

    public static byte[] zip(List<ZipItem> entries) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
                for (ZipItem item : entries) {
                    ZipEntry entry = new ZipEntry(item.name());
                    entry.setMethod(item.method());
                    if (item.method() == ZipEntry.STORED) {
                        CRC32 crc = new CRC32();
                        crc.update(item.bytes());
                        entry.setSize(item.bytes().length);
                        entry.setCompressedSize(item.bytes().length);
                        entry.setCrc(crc.getValue());
                    }
                    zip.putNextEntry(entry);
                    if (!item.directory()) {
                        zip.write(item.bytes());
                    }
                    zip.closeEntry();
                }
            }
            return output.toByteArray();
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    public static byte[] encryptedFirstEntry(byte[] ordinaryZip) {
        byte[] encrypted = ordinaryZip.clone();
        if (readInt(encrypted, 0) != 0x04034b50) {
            throw new IllegalArgumentException("ZIP fixture has no local header");
        }
        encrypted[6] = (byte) (encrypted[6] | 0x01);
        int central = indexOf(encrypted, new byte[]{'P', 'K', 1, 2});
        if (central >= 0) {
            encrypted[central + 8] = (byte) (encrypted[central + 8] | 0x01);
        }
        return encrypted;
    }

    public static byte[] corruptFirstEntryData(byte[] ordinaryZip) {
        byte[] corrupted = ordinaryZip.clone();
        int nameLength = readShort(corrupted, 26);
        int extraLength = readShort(corrupted, 28);
        int dataOffset = 30 + nameLength + extraLength;
        int central = indexOf(corrupted, new byte[]{'P', 'K', 1, 2});
        int flip = dataOffset + Math.max(0, (central - dataOffset) / 2);
        corrupted[flip] ^= 0x40;
        return corrupted;
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int index = 0; index <= haystack.length - needle.length; index++) {
            for (int offset = 0; offset < needle.length; offset++) {
                if (haystack[index + offset] != needle[offset]) {
                    continue outer;
                }
            }
            return index;
        }
        return -1;
    }

    private static int readShort(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) | (bytes[offset + 1] & 0xff) << 8;
    }

    private static int readInt(byte[] bytes, int offset) {
        return readShort(bytes, offset) | readShort(bytes, offset + 2) << 16;
    }

    public record ZipItem(String name, byte[] bytes, boolean directory, int method) {
        public ZipItem {
            bytes = Arrays.copyOf(bytes, bytes.length);
        }

        @Override
        public byte[] bytes() {
            return Arrays.copyOf(bytes, bytes.length);
        }
    }
}

package com.mapsyncer.server;

import com.mapsyncer.mca.RegionConverterStandalone.ConvertedRegion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes converted region data as the zip files Xaero's World Map reads.
 *
 * Output is {@code {outputDir}/{regionX}_{regionZ}.zip} containing a single
 * {@code region.xaero} entry. Written to a temporary file and then moved into place, so a
 * partially written file is never visible.
 */
public class XaeroWriter {

    /**
     * Writes one converted region to its zip file.
     *
     * @param outputDir directory to write into
     * @param region the converted region data
     * @return the path of the zip that was written
     * @throws IOException if writing fails
     */
    public static Path writeRegionFile(Path outputDir, ConvertedRegion region) throws IOException {
        Files.createDirectories(outputDir);

        String fileName = region.regionX() + "_" + region.regionZ();
        Path tempFile = outputDir.resolve(fileName + ".zip.temp");
        Path finalFile = outputDir.resolve(fileName + ".zip");

        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(tempFile))) {
            ZipEntry entry = new ZipEntry("region.xaero");
            zos.putNextEntry(entry);
            zos.write(region.xaeroData());
            zos.closeEntry();
        }

        Files.move(tempFile, finalFile, StandardCopyOption.REPLACE_EXISTING);
        return finalFile;
    }

    /**
     * Whether a region file already exists.
     *
     * @param outputDir directory to look in
     * @param regionX region X coordinate
     * @param regionZ region Z coordinate
     * @return {@code true} if the file is already there
     */
    public static boolean regionFileExists(Path outputDir, int regionX, int regionZ) {
        Path zipFile = outputDir.resolve(regionX + "_" + regionZ + ".zip");
        return Files.exists(zipFile);
    }
}

/*
 * MIT License
 *
 * Copyright (c) 2019-present, iQIYI, Inc. All rights reserved.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.iqiyi.qigsaw.buildtool.gradle.internal.tool

import java.nio.charset.Charset
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.regex.Matcher
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

class FileUtils {

    static void unZipFile(File zipFile, String descDir) throws IOException {
        ZipFile zip = new ZipFile(zipFile, Charset.forName("GBK"))
        for (Enumeration<? extends ZipEntry> entries = zip.entries(); entries.hasMoreElements();) {
            ZipEntry entry = (ZipEntry) entries.nextElement()
            String zipEntryName = entry.getName()
            InputStream is = zip.getInputStream(entry)
            String outPath = (descDir + File.separator + zipEntryName).replaceAll("/", Matcher.quoteReplacement(File.separator))
            File file = new File(outPath.substring(0, outPath.lastIndexOf(File.separator)))
            if (!file.exists()) {
                file.mkdirs()
            }
            if (new File(outPath).isDirectory()) {
                continue
            }
            FileOutputStream out = new FileOutputStream(outPath)
            byte[] buf1 = new byte[2048]
            int len
            while ((len = is.read(buf1)) > 0) {
                out.write(buf1, 0, len)
            }
            closeQuietly(is)
            closeQuietly(out)
        }
    }

    static String getMD5(File file) {
        MessageDigest digest
        try {
            digest = MessageDigest.getInstance("MD5")
        } catch (NoSuchAlgorithmException e) {
            return null
        }
        InputStream is
        try {
            is = new FileInputStream(file)
        } catch (FileNotFoundException e) {
            return null
        }

        byte[] buffer = new byte[8192]
        int read
        try {
            while ((read = is.read(buffer)) > 0) {
                digest.update(buffer, 0, read)
            }
            byte[] md5sum = digest.digest()
            BigInteger bigInt = new BigInteger(1, md5sum)
            String output = bigInt.toString(16)
            // Fill to 32 chars
            output = String.format("%32s", output).replace(' ', '0')
            return output
        } catch (IOException e) {
            throw new RuntimeException("Unable to process file for MD5", e)
        } finally {
            closeQuietly(is)
        }
    }

    static void copyFile(InputStream source, OutputStream dest)
            throws IOException {
        InputStream is = source
        OutputStream os = dest
        try {
            byte[] buffer = new byte[1024]
            int length
            while ((length = is.read(buffer)) > 0) {
                os.write(buffer, 0, length)
            }
        } finally {
            closeQuietly(is)
            closeQuietly(os)
        }
    }


    static void copyFile(File source, File dest)
            throws IOException {
        copyFile(new FileInputStream(source), new FileOutputStream(dest))
    }

    static void closeQuietly(Closeable obj) {
        if (obj != null) {
            try {
                obj.close()
            } catch (Exception e) {
                e.printStackTrace()
            }
        }
    }
}

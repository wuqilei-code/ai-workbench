package com.aiworkbench.service.opencode;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

/**
 * 屏幕脏矩形差分器(网关侧):
 * 按网格切块做 hash 比对上一帧,算出「变化区域」的包围盒(而非逐块重编码),每帧只编码一次:
 * <ul>
 *   <li>局部变化:只编码一小块区域,payload 小;</li>
 *   <li>整屏变化:编码一次整帧(而非 N 块),避免逐块 JPEG 编码拖慢帧率。</li>
 * </ul>
 * 静止时返回 null(零流量)。
 */
public class ScreenTileDiffer {

    /** 差异检测块尺寸:决定脏矩形粒度(非编码粒度) */
    private static final int TILE_W = 160;
    private static final int TILE_H = 120;

    public record Result(int x, int y, int w, int h, byte[] jpeg, boolean resized) {
    }

    private int cols;
    private int rows;
    private int width;
    private int height;
    private long[] prevHash;
    private boolean[] seen;
    private final int[] pixBuf = new int[TILE_W * TILE_H];
    private ImageWriter jpegWriter;
    private ImageWriteParam jpegParam;

    /** 处理一帧:返回脏矩形(resized=true 表示尺寸变化,需重设画布);无变化返回 null。 */
    public Result process(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        if (w != width || h != height || prevHash == null) {
            width = w;
            height = h;
            cols = (w + TILE_W - 1) / TILE_W;
            rows = (h + TILE_H - 1) / TILE_H;
            prevHash = new long[cols * rows];
            seen = new boolean[cols * rows];
            return new Result(0, 0, w, h, encode(img, 0, 0, w, h), true);
        }

        int minTx = Integer.MAX_VALUE, minTy = Integer.MAX_VALUE;
        int maxTx = -1, maxTy = -1;
        boolean any = false;
        for (int ty = 0; ty < rows; ty++) {
            for (int tx = 0; tx < cols; tx++) {
                int x = tx * TILE_W;
                int y = ty * TILE_H;
                int tw = Math.min(TILE_W, w - x);
                int th = Math.min(TILE_H, h - y);
                int idx = ty * cols + tx;
                long hash = hash(img, x, y, tw, th);
                if (seen[idx] && prevHash[idx] == hash) {
                    continue;
                }
                seen[idx] = true;
                prevHash[idx] = hash;
                any = true;
                if (tx < minTx) minTx = tx;
                if (tx > maxTx) maxTx = tx;
                if (ty < minTy) minTy = ty;
                if (ty > maxTy) maxTy = ty;
            }
        }
        if (!any) {
            return null;
        }
        int bx = minTx * TILE_W;
        int by = minTy * TILE_H;
        int bw = Math.min(w, (maxTx + 1) * TILE_W) - bx;
        int bh = Math.min(h, (maxTy + 1) * TILE_H) - by;
        byte[] jpeg = encode(img, bx, by, bw, bh);
        if (jpeg == null || jpeg.length == 0) {
            return null;
        }
        return new Result(bx, by, bw, bh, jpeg, false);
    }

    private long hash(BufferedImage img, int x, int y, int w, int h) {
        img.getRGB(x, y, w, h, pixBuf, 0, w);
        long hash = 1125899906842597L;
        int n = w * h;
        for (int i = 0; i < n; i++) {
            hash = hash * 31 + pixBuf[i];
        }
        return hash;
    }

    private byte[] encode(BufferedImage img, int x, int y, int w, int h) {
        try {
            BufferedImage sub = img.getSubimage(x, y, w, h);
            if (jpegWriter == null) {
                jpegWriter = ImageIO.getImageWritersByFormatName("jpeg").next();
                jpegParam = jpegWriter.getDefaultWriteParam();
                jpegParam.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                jpegParam.setCompressionQuality(0.55f);
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream(64 * 1024);
            MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(bos);
            jpegWriter.setOutput(out);
            jpegWriter.write(null, new IIOImage(sub, null, null), jpegParam);
            out.close();
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }
}

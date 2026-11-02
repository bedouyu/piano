package com.shipu.assistant;

import android.graphics.*;
import android.graphics.pdf.PdfRenderer;
import android.media.ExifInterface;
import android.os.ParcelFileDescriptor;
import android.util.Base64;
import org.json.JSONObject;
import java.io.*;

final class ScoreImageRenderer {
    static int pages(File file, String mime) throws Exception {
        if (!"application/pdf".equals(mime)) return 1;
        try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY); PdfRenderer pdf = new PdfRenderer(descriptor)) { return pdf.getPageCount(); }
    }
    static JSONObject render(File file, String mime, int page, boolean recognition) throws Exception {
        int count = pages(file, mime);
        if (page < 0 || page >= count) throw new IOException("页码超出这份乐谱的范围");
        Bitmap bitmap = null;
        try {
            int maxWidth = recognition ? 2200 : 1400, maxHeight = 5000;
            if ("application/pdf".equals(mime)) {
                try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
                     PdfRenderer pdf = new PdfRenderer(descriptor); PdfRenderer.Page source = pdf.openPage(page)) {
                    double scale = Math.min(maxWidth / (double)source.getWidth(), maxHeight / (double)source.getHeight());
                    bitmap = Bitmap.createBitmap(Math.max(1, (int)Math.round(source.getWidth() * scale)), Math.max(1, (int)Math.round(source.getHeight() * scale)), Bitmap.Config.ARGB_8888);
                    new Canvas(bitmap).drawColor(Color.WHITE); source.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                }
            } else {
                BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true; BitmapFactory.decodeFile(file.getPath(), options);
                if (options.outWidth < 1 || options.outHeight < 1) throw new IOException("这张图片无法读取，请转为 JPG 或 PNG 后导入");
                int sample = 1; while (options.outWidth / (sample * 2L) >= maxWidth || options.outHeight / (sample * 2L) >= maxHeight) sample *= 2;
                options.inJustDecodeBounds = false; options.inSampleSize = sample; options.inPreferredConfig = Bitmap.Config.ARGB_8888;
                bitmap = BitmapFactory.decodeFile(file.getPath(), options);
                if (bitmap == null) throw new IOException("图片无法读取");
                Matrix matrix = new Matrix();
                try {
                    switch (new ExifInterface(file.getPath()).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                        case 2: matrix.setScale(-1, 1); break; case 3: matrix.setRotate(180); break;
                        case 4: matrix.setScale(1, -1); break; case 5: matrix.setRotate(90); matrix.postScale(-1, 1); break;
                        case 6: matrix.setRotate(90); break; case 7: matrix.setRotate(-90); matrix.postScale(-1, 1); break;
                        case 8: matrix.setRotate(-90); break;
                    }
                } catch (IOException ignored) { }
                if (!matrix.isIdentity()) { Bitmap corrected = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true); if (corrected != bitmap) { bitmap.recycle(); bitmap = corrected; } }
                double scale = Math.min(1, Math.min(maxWidth / (double)bitmap.getWidth(), maxHeight / (double)bitmap.getHeight()));
                if (scale < 1) { Bitmap smaller = Bitmap.createScaledBitmap(bitmap, Math.max(1, (int)(bitmap.getWidth() * scale)), Math.max(1, (int)(bitmap.getHeight() * scale)), true); if (smaller != bitmap) { bitmap.recycle(); bitmap = smaller; } }
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); String outputMime = recognition ? "image/png" : "image/jpeg";
            if (!bitmap.compress(recognition ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, recognition ? 100 : 88, bytes)) throw new IOException("页面转换失败");
            for (int attempt = 0; bytes.size() > 4 * 1024 * 1024 && attempt < 6; attempt++) {
                if (attempt > 0) { Bitmap smaller = Bitmap.createScaledBitmap(bitmap, Math.max(1, bitmap.getWidth() * 4 / 5), Math.max(1, bitmap.getHeight() * 4 / 5), true); if (smaller != bitmap) { bitmap.recycle(); bitmap = smaller; } }
                bytes.reset(); outputMime = "image/jpeg"; bitmap.compress(Bitmap.CompressFormat.JPEG, 94, bytes);
            }
            if (bytes.size() > 4 * 1024 * 1024) throw new IOException("这一页过大，请缩小后重试");
            return new JSONObject().put("page", page).put("pages", count).put("mime", outputMime).put("base64", Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP));
        } finally { if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle(); }
    }
}

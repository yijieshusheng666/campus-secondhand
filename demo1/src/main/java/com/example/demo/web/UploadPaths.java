package com.example.demo.web;

import java.io.File;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 商品图片上传目录的统一解析。
 *
 * <p>为什么抽出来：写入方（GoodsController）和对外静态映射（Demo1Application）
 * 都需要知道「文件到底在哪」。以前两边各写一份
 * {@code System.getProperty("user.dir") + "/uploads"}，而 user.dir
 * 取决于你从哪个目录启动 —— 从 demo1/ 起是 demo1/uploads，换个目录起
 * 就变成另一个服务自己都找不到的地方：上传接口返回成功，图片却永远 404。
 * 两份逻辑重复是这个坑能潜伏下来的根本原因。</p>
 */
public final class UploadPaths {

    private static final Logger log = LoggerFactory.getLogger(UploadPaths.class);

    /** 配置项；同时允许用 -Dapp.upload.dir 覆盖，方便临时改位置 */
    private static final String KEY = "app.upload.dir";

    private UploadPaths() {
    }

    /** 上传目录的绝对路径（不保证目录已存在）。 */
    public static File dir() {
        String configured = System.getProperty(KEY);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv().getOrDefault("APP_UPLOAD_DIR", "./uploads");
        }
        File f = new File(configured.trim());
        if (f.isAbsolute()) {
            return f;
        }
        // 相对路径一律相对启动目录解析。用 getCanonicalFile 而不是 getAbsoluteFile：
        // 配置项写 "./uploads" 时，后者会留下带一个多余 "." 段的路径，
        // 文件系统认得，但日志和控制台里看着像坏了。
        try {
            return new File(System.getProperty("user.dir"), configured.trim()).getCanonicalFile();
        } catch (java.io.IOException e) {
            return new File(System.getProperty("user.dir"), configured.trim()).getAbsoluteFile();
        }
    }

    /**
     * 拿到「确保存在」的上传目录。
     *
     * <p>以前这里写的是 {@code if (!folder.exists()) folder.mkdirs();}，
     * 完全忽略返回值 —— 建目录失败（比如没权限）不会立刻报错，
     * 要等到 transferTo 才抛一个语焉不详的 IO 异常。
     * 这里显式检查并抛出可读的错误信息。</p>
     */
    public static File ensureDir() {
        File dir = dir();
        if (dir.isDirectory()) {
            return dir;
        }
        if (!dir.mkdirs() && !dir.isDirectory()) {
            throw new IllegalStateException(
                    "无法创建商品图片上传目录：" + dir.getAbsolutePath()
                            + "（请检查权限，或用 -D" + KEY + " 指定其它位置）");
        }
        log.info("商品图片上传目录已就绪：{}", dir.getAbsolutePath());
        return dir;
    }
}

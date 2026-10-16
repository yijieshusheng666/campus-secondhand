package com.example.demo.controller;

import java.io.File;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.alibaba.fastjson2.JSONObject;
import com.example.demo.auth.AuthContext;
import com.example.demo.entity.Goods;
import com.example.demo.web.UploadPaths;
import com.example.demo.entity.GoodsImage;
import com.example.demo.repository.GoodsImageRepository;
import com.example.demo.repository.GoodsRepository;
import com.example.demo.service.AiClient;

/**
 * 商品 CRUD（C2）+ 发布图片上传。
 *
 * <p>前端 API 路径约定：{@code /api/goods} 仍由这一个 Controller 整体负责，
 * 不能拆成更细的 Controller，否则会导致路径改变和前端代码联动改动。</p>
 */
@RestController
@RequestMapping("/api/goods")
public class GoodsController {

    private static final Logger logger = LoggerFactory.getLogger(GoodsController.class);

    /**
     * 允许上传的图片扩展名白名单。
     *
     * <p>以前只判断文件名里「有没有点」，然后把扩展名原样拼进保存路径。两个后果：
     * 传 .html / .svg 会被存成 {@code /uploads/xxx.html}，而该目录被映射为同源静态资源
     * ——等于在同源下拿到一个存储型 XSS；构造形如 {@code a./../../x} 的文件名还能让
     * 保存路径逃出上传目录。有了白名单，落盘文件名只可能是 {@code UUID.已知后缀}。</p>
     */
    private static final Set<String> ALLOWED_IMAGE_EXT =
            Set.of("jpg", "jpeg", "png", "webp", "gif");

    @Autowired
    private GoodsRepository goodsRepository;

    @Autowired
    private GoodsImageRepository goodsImageRepository;

    @Autowired
    private AiClient aiClient;

    // 获取商品列表：支持分类、关键词筛选，已售商品不下架但不在列表中展示
    @GetMapping
    public List<Goods> list(@RequestParam(required = false) String category,
                            @RequestParam(required = false) String keyword) {
        return goodsRepository.findAll().stream()
                .filter(g -> !"已售".equals(g.getStatus()))
                .filter(g -> category == null || category.isBlank() || category.equals(g.getCategory()))
                .filter(g -> {
                    if (keyword == null || keyword.isBlank()) return true;
                    // 关键词同时匹配课程号，所以搜「CS101」也能找到对应教材
                    String text = g.getTitle() + g.getCategory() + g.getDescription()
                            + (g.getCourseCode() == null ? "" : g.getCourseCode());
                    return text.contains(keyword.trim());
                })
                .toList();
    }

    // 获取单个商品详情
    @GetMapping("/{id}")
    public ResponseEntity<Goods> getById(@PathVariable Long id) {
        Goods goods = goodsRepository.findById(id).orElse(null);
        if (goods == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(goods);
    }

    // 删除商品。以前任何人拿到 id 就能删别人的商品，这里补上归属校验：只有发布者本人或管理员可以删。
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Long id) {
        try {
            Goods goods = goodsRepository.findById(id).orElse(null);
            if (goods == null) {
                return ResponseEntity.notFound().build();
            }
            // 写成 requireUserId().equals(...) 而不是 goods.getUserId().equals(...)：
            // 历史数据里可能存在 userId 为 null 的商品，反过来调会 NPE，
            // 然后被下面的 catch 兜成一个「message 为 null 的 400」，看不出真实原因。
            if (!AuthContext.isAdmin() && !AuthContext.requireUserId().equals(goods.getUserId())) {
                Map<String, Object> forbidden = new LinkedHashMap<>();
                forbidden.put("success", false);
                forbidden.put("message", "只能删除自己发布的商品");
                return ResponseEntity.status(403).body(forbidden);
            }
            goodsRepository.deleteById(id);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            body.put("message", "商品删除成功");
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            // 不回 e.getMessage()：Hibernate 的异常文本带表名/列名、有时还带 SQL 片段，
            // 等于免费给攻击者一份表结构。详情进服务端日志。
            logger.warn("删除商品失败 id={}", id, e);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", false);
            body.put("message", "删除失败，请稍后重试");
            return ResponseEntity.badRequest().body(body);
        }
    }

    // 发布商品（带图片上传）
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(
            @RequestParam String title,
            @RequestParam String category,
            @RequestParam BigDecimal price,
            @RequestParam String description,
            @RequestParam(value = "courseCode", required = false) String courseCode,
            @RequestParam(value = "images", required = false) MultipartFile[] images) {
        try {
            // 发布者取自 token：以前是 @RequestParam userId，等于可以替别人发商品
            Long userId = AuthContext.requireUserId();

            // A6 内容风控：违禁内容直接拒绝发布。前端发布前也会查一次，
            // 这里是后端兜底——绕过前端也拦得住。
            JSONObject check = aiClient.moderate(title, description);
            if (check != null && "block".equals(check.getString("level"))) {
                Map<String, Object> rejected = new LinkedHashMap<>();
                rejected.put("success", false);
                rejected.put("message", check.getString("message"));
                rejected.put("level", "block");
                return ResponseEntity.badRequest().body(rejected);
            }

            Goods goods = new Goods();
            goods.setTitle(title);
            goods.setCategory(category);
            goods.setPrice(price);
            goods.setDescription(description);
            goods.setUserId(userId);
            // C2：教材类商品可关联课程号
            goods.setCourseCode(courseCode == null || courseCode.isBlank() ? null : courseCode.trim());
            goods = goodsRepository.save(goods);

            if (images != null) {
                for (MultipartFile image : images) {
                    if (image.isEmpty()) continue;
                    String original = image.getOriginalFilename();
                    String suffix = original == null ? null
                            : StringUtils.getFilenameExtension(original);
                    if (suffix == null || !ALLOWED_IMAGE_EXT.contains(suffix.toLowerCase())) {
                        Map<String, Object> rejected = new LinkedHashMap<>();
                        rejected.put("success", false);
                        rejected.put("message", "图片只支持 jpg / png / webp / gif 格式");
                        return ResponseEntity.badRequest().body(rejected);
                    }
                    String filename = UUID.randomUUID() + "." + suffix.toLowerCase();

                    // 路径解析和静态映射共用 UploadPaths.ensureDir()，
                    // 不再自己拼 user.dir（那会随启动位置漂移），建目录失败也会直接报可读的错误
                    File saved = new File(UploadPaths.ensureDir(), filename);
                    image.transferTo(saved);

                    GoodsImage gi = new GoodsImage();
                    gi.setGoodsId(goods.getId());
                    gi.setImageUrl("/uploads/" + filename);
                    goodsImageRepository.save(gi);
                }
            }

            // 发布成功后推送索引，使新商品立即能被语义搜索命中；失败不影响发布结果
            aiClient.indexGoods(goods.getId(), AiController.buildIndexText(goods));

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            body.put("goodsId", goods.getId());
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            // 同上：不让 e.getMessage() 直接见客户端
            logger.warn("发布商品失败 userId={}", AuthContext.userIdOrNull(), e);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", false);
            body.put("message", "发布失败，请稍后重试");
            return ResponseEntity.badRequest().body(body);
        }
    }
}

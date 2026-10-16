package com.example.demo.controller;

import java.io.File;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.alibaba.fastjson2.JSONObject;
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

    // 删除商品
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Long id) {
        try {
            if (goodsRepository.existsById(id)) {
                goodsRepository.deleteById(id);
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("success", true);
                body.put("message", "商品删除成功");
                return ResponseEntity.ok(body);
            }
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", false);
            body.put("message", e.getMessage());
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
            @RequestParam Long userId,
            @RequestParam(value = "courseCode", required = false) String courseCode,
            @RequestParam(value = "images", required = false) MultipartFile[] images) {
        try {
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
                    if (original == null || !original.contains(".")) continue;
                    String suffix = original.substring(original.lastIndexOf(".") + 1);
                    String filename = UUID.randomUUID() + "." + suffix;

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
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", false);
            body.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(body);
        }
    }
}

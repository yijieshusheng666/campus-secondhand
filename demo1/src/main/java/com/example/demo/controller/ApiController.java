package com.example.demo.controller;

import java.io.File;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.alibaba.fastjson2.JSON;
import com.example.demo.entity.Goods;
import com.example.demo.entity.GoodsImage;
import com.example.demo.entity.User;
import com.example.demo.repository.GoodsImageRepository;
import com.example.demo.repository.GoodsRepository;
import com.example.demo.repository.UserRepository;

@RestController
@RequestMapping("/api")
public class ApiController {

    // API 根路径
    @GetMapping("")
    public ResponseEntity<?> index() {
        return ResponseEntity.ok(Map.of("success", true, "message", "API 服务运行正常", "version", "2.0.0"));
    }

    @Autowired
    private GoodsRepository goodsRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private GoodsImageRepository goodsImageRepository;

    // 获取商品列表：支持分类、关键词筛选，已售商品不下架但不在列表中展示
    @GetMapping("/goods")
    public List<Goods> getGoods(@RequestParam(required = false) String category,
                                @RequestParam(required = false) String keyword) {
        return goodsRepository.findAll().stream()
                .filter(g -> !"已售".equals(g.getStatus()))
                .filter(g -> category == null || category.isBlank() || category.equals(g.getCategory()))
                .filter(g -> {
                    if (keyword == null || keyword.isBlank()) return true;
                    String text = g.getTitle() + g.getCategory() + g.getDescription();
                    return text.contains(keyword.trim());
                })
                .toList();
    }

    // 获取单个商品详情
    @GetMapping("/goods/{id}")
    public ResponseEntity<?> getGoodsById(@PathVariable Long id) {
        Goods goods = goodsRepository.findById(id).orElse(null);
        if (goods != null) {
            return ResponseEntity.ok(goods);
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    // 删除商品
    @DeleteMapping("/goods/{id}")
    public ResponseEntity<?> deleteGoods(@PathVariable Long id) {
        try {
            if (goodsRepository.existsById(id)) {
                goodsRepository.deleteById(id);
                return ResponseEntity.ok("{\"success\":true,\"message\":\"商品删除成功\"}");
            } else {
                return ResponseEntity.notFound().build();
            }
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("{\"success\":false,\"message\":\"" + e.getMessage() + "\"}");
        }
    }

    // 发布商品（带图片上传）
    @PostMapping("/goods")
    public ResponseEntity<?> addGoods(
            @RequestParam String title,
            @RequestParam String category,
            @RequestParam BigDecimal price,
            @RequestParam String description,
            @RequestParam Long userId,
            @RequestParam(value = "images", required = false) MultipartFile[] images
    ) {
        try {
            Goods goods = new Goods();
            goods.setTitle(title);
            goods.setCategory(category);
            goods.setPrice(price);
            goods.setDescription(description);
            goods.setUserId(userId);
            goods = goodsRepository.save(goods);

            if (images != null) {
                for (MultipartFile image : images) {
                    if (!image.isEmpty()) {
                        String original = image.getOriginalFilename();
                        if (original == null || !original.contains(".")) {
                            continue;
                        }
                        String suffix = original.substring(original.lastIndexOf(".") + 1);
                        String filename = UUID.randomUUID() + "." + suffix;

                        String projectRoot = System.getProperty("user.dir");
                        String uploadsDir = projectRoot + "/uploads";
                        String path = "/uploads/" + filename;

                        File folder = new File(uploadsDir);
                        if (!folder.exists()) folder.mkdirs();

                        image.transferTo(new File(uploadsDir + "/" + filename));

                        GoodsImage gi = new GoodsImage();
                        gi.setGoodsId(goods.getId());
                        gi.setImageUrl(path);
                        goodsImageRepository.save(gi);
                    }
                }
            }

            return ResponseEntity.ok("{\"success\":true,\"goodsId\":" + goods.getId() + "}");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("{\"success\":false,\"msg\":\"" + e.getMessage() + "\"}");
        }
    }

    // 登录
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestParam String stuid, @RequestParam String password) {
        User user = userRepository.findByStuidAndPassword(stuid, password);
        if (user != null) {
            String userJson = JSON.toJSONString(user);
            return ResponseEntity.ok("{\"success\":true,\"user\":" + userJson + "}");
        } else {
            return ResponseEntity.ok("{\"success\":false,\"msg\":\"学号或密码错误\"}");
        }
    }

    // 注册
    @PostMapping("/register")
    @Transactional
    public ResponseEntity<?> register(@RequestParam String username,
                                      @RequestParam String stuid,
                                      @RequestParam String password) {
        try {
            // 检查学号是否已存在
            User existingUser = userRepository.findByStuid(stuid);
            if (existingUser != null) {
                return ResponseEntity.ok().body("{\"success\": false, \"message\": \"学号已被注册\"}");
            }

            // 创建新用户
            User user = new User();
            user.setUsername(username);
            user.setStuid(stuid);
            user.setPassword(password);
            user = userRepository.save(user);

            return ResponseEntity.ok().body("{\"success\": true, \"userId\": " + user.getId() + "}");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("{\"success\": false, \"message\": \"" + e.getMessage() + "\"}");
        }
    }

    // 修改用户昵称
    @PostMapping("/user/update")
    @Transactional
    public ResponseEntity<?> updateUser(@RequestBody Map<String, Object> body) {
        Long id = Long.valueOf(body.get("id").toString());
        String username = (String) body.get("username");
        if (username == null || username.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "昵称不能为空"));
        }
        User user = userRepository.findById(id).orElse(null);
        if (user == null) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "用户不存在"));
        }
        user.setUsername(username.trim());
        userRepository.save(user);
        return ResponseEntity.ok(Map.of("success", true, "message", "昵称修改成功"));
    }

    // 查询用户基本信息（卖家昵称展示、沟通对象展示用）
    @GetMapping("/users/{id}")
    public ResponseEntity<?> getUserById(@PathVariable Long id) {
        User user = userRepository.findById(id).orElse(null);
        if (user == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of(
                "id", user.getId(),
                "username", user.getUsername(),
                "stuid", user.getStuid() == null ? "" : user.getStuid()));
    }

    // 根据需求关键词匹配推荐商品
    @PostMapping("/recommend")
    public ResponseEntity<?> recommend(@RequestBody Map<String, String> request) {
        String keyword = request.get("keyword");
        if (keyword == null || keyword.trim().isEmpty()) {
            return ResponseEntity.ok(Map.of("success", false, "message", "请输入搜索关键词"));
        }
        keyword = keyword.trim();

        // 去掉口语化字词提取核心词，如"我想买一副耳机"→"耳机"
        String cleaned = keyword.replaceAll(
                "[我想买求要帮找请问需要一副台件部只个本有没在吗呢啊了吧哦了是与和还,，。！!？?~～\\s]", "");

        List<Goods> allGoods = goodsRepository.findAll();
        List<Map<String, Object>> results = new ArrayList<>();

        for (Goods goods : allGoods) {
            // 已售商品不再推荐
            if ("已售".equals(goods.getStatus())) {
                continue;
            }
            int score = 0;
            String title = goods.getTitle() != null ? goods.getTitle() : "";
            String category = goods.getCategory() != null ? goods.getCategory() : "";

            // 完全匹配标题加分最多
            if (title.contains(keyword)) score += 10;
            // 匹配分类加分
            if (category.contains(keyword)) score += 5;

            // 核心词匹配
            if (!cleaned.isEmpty() && !cleaned.equals(keyword)) {
                if (title.contains(cleaned)) score += 8;
                if (category.contains(cleaned)) score += 4;
            }

            // 中文长句兜底：2 字滑窗切分，自然语句也能命中商品
            String source = cleaned.isEmpty() ? keyword : cleaned;
            if (source.length() > 2) {
                for (int i = 0; i + 2 <= source.length(); i++) {
                    String gram = source.substring(i, i + 2);
                    if (title.contains(gram)) score += 2;
                    if (category.contains(gram)) score += 1;
                }
            }

            // 英文/多词输入：按空格拆词匹配
            String[] words = keyword.split("[\\s,，]+");
            for (String word : words) {
                if (word.isEmpty()) continue;
                if (title.contains(word)) score += 3;
                if (category.contains(word)) score += 2;
            }

            if (score > 0) {
                Map<String, Object> item = new HashMap<>();
                item.put("id", goods.getId());
                item.put("title", goods.getTitle());
                item.put("price", goods.getPrice());
                item.put("category", goods.getCategory());
                item.put("score", score);
                results.add(item);
            }
        }

        // 按匹配分数降序排序
        results.sort((a, b) -> ((Integer) b.get("score")).compareTo((Integer) a.get("score")));

        // 最多返回 5 条
        if (results.size() > 5) {
            results = results.subList(0, 5);
        }

        return ResponseEntity.ok(Map.of("success", true, "data", results));
    }
}
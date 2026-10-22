package com.example.demo;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.example.demo.entity.Goods;
import com.example.demo.entity.GoodsImage;
import com.example.demo.entity.User;
import com.example.demo.repository.GoodsImageRepository;
import com.example.demo.repository.GoodsRepository;
import com.example.demo.repository.UserRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 启动时初始化两个内置账号 + 一批示例商品。
 *
 * <p>示例商品从 {@code resources/seed-goods.json} 读——之前 30 件商品写在 Java 里
 * 占了 ~250 行，要新增 / 调整商品得改代码并重新打包；改成数据驱动后，运营同学
 * 直接改 JSON 就行。</p>
 */
@Component
public class DataInitializer implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(DataInitializer.class);

    private static final String SEED_GOODS_FILE = "seed-goods.json";

    @Autowired
    private GoodsRepository goodsRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private GoodsImageRepository goodsImageRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) throws Exception {
        logger.info("开始初始化数据...");

        // 1) 内置账号：admin（平台管理员）+ test（普通用户）
        User admin = ensureUser("管理员", "admin", "admin123", "admin");
        User testUser = ensureUser("测试用户", "test", "test123", "user");

        // 2) 商品数据：首次启动写入示例库；老库有数据时跳过
        if (goodsRepository.count() == 0 && admin != null && testUser != null) {
            Map<String, User> owners = new HashMap<>();
            owners.put("admin", admin);
            owners.put("test", testUser);

            List<SeedGoods> seeds = readSeedGoods();
            if (seeds.isEmpty()) {
                logger.warn("示例商品为空（{} 缺失或格式错误），跳过种子数据初始化", SEED_GOODS_FILE);
            }
            for (SeedGoods seed : seeds) {
                User owner = owners.get(seed.ownerStuid);
                if (owner == null) continue;        // ownerStuid 填错就忽略这条
                Goods g = new Goods();
                g.setTitle(seed.title);
                g.setCategory(seed.category);
                g.setPrice(BigDecimal.valueOf(seed.price));
                g.setDescription(seed.description);
                g.setUserId(owner.getId());
                g = goodsRepository.save(g);
                if (seed.imageColor != null && seed.imageText != null) {
                    String url = "https://placehold.co/400x400/" + seed.imageColor + "/white?text=" + seed.imageText;
                    GoodsImage img = new GoodsImage();
                    img.setGoodsId(g.getId());
                    img.setImageUrl(url);
                    goodsImageRepository.save(img);
                }
                logger.info("初始化商品：{}", g.getTitle());
            }
        }

        logger.info("数据初始化完成！");
    }

    /**
     * 保证账号存在；若老库账号的 role 字段为空（旧库兼容），启动时回填一次。
     *
     * <p>⚠️ 这里曾经用 {@code userRepository.count() == 0} 当「要不要新建」的判据，
     * 是一个只在**全新数据库**上才暴露的 bug：第一个账号 admin 建成后 count 变成 1，
     * 第二个账号 test 就直接跳过创建、findByStuid 拿到 null，
     * 于是 {@code run()} 里 {@code admin != null && testUser != null} 不成立，
     * <b>整批种子商品都不会写入</b>。表现是「启动日志写着初始化完成，但库里 0 件商品」。
     * 本地开发库早就有了这两个账号，所以一直没发现；换台干净服务器部署立刻现形。</p>
     *
     * <p>正确判据是「这个学号是否已存在」，与表里有多少人无关。</p>
     */
    private User ensureUser(String username, String stuid, String password, String role) {
        User existing = userRepository.findByStuid(stuid);
        if (existing == null) {
            User u = new User();
            u.setUsername(username);
            u.setStuid(stuid);
            // 内置账号也必须存哈希：以前这里是明文口令，
            // 一次库泄漏就等于把管理员口令白送出去。
            u.setPassword(passwordEncoder.encode(password));
            u.setRole(role);
            u = userRepository.save(u);
            logger.info("初始化用户：{}，ID：{}", u.getUsername(), u.getId());
            return u;
        }
        if (role != null && !role.equals(existing.getRole())) {
            existing.setRole(role);
            userRepository.save(existing);
        }
        return existing;
    }

    /**
     * 读取示例商品。
     *
     * <p>刻意不外抛：{@code CommandLineRunner} 抛异常会中止整个应用启动，而种子数据只是
     * 演示用的锦上添花。文件缺失或格式错误时应降级为「没有示例商品」，而不是让后端起不来
     * ——新克隆的仓库最容易踩这个坑。</p>
     */
    private List<SeedGoods> readSeedGoods() {
        try (InputStream in = new ClassPathResource(SEED_GOODS_FILE).getInputStream()) {
            return new ObjectMapper().readValue(in, new TypeReference<List<SeedGoods>>() {});
        } catch (Exception e) {
            logger.warn("读取 {} 失败：{}", SEED_GOODS_FILE, e.getMessage());
            return List.of();
        }
    }

    /** 种子商品 JSON 的字段映射；字段命名直接跟 JSON 走，省一层转换。 */
    public static class SeedGoods {
        public String title;
        public String category;
        public double price;
        public String description;
        public String ownerStuid;
        public String imageColor;
        public String imageText;
    }
}

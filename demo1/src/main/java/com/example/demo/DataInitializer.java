package com.example.demo;

import java.math.BigDecimal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import com.example.demo.entity.Goods;
import com.example.demo.entity.GoodsImage;
import com.example.demo.entity.User;
import com.example.demo.repository.GoodsImageRepository;
import com.example.demo.repository.GoodsRepository;
import com.example.demo.repository.UserRepository;

@Component
public class DataInitializer implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(DataInitializer.class);

    @Autowired
    private GoodsRepository goodsRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private GoodsImageRepository goodsImageRepository;

    // 分类配色方案（placehold.co 稳定占位图，同一URL图片永不改变）
    private static final String COLOR_ELECTRONIC = "2563EB";   // 蓝色 - 电子产品
    private static final String COLOR_BOOK      = "EA580C";   // 橙色 - 图书教材
    private static final String COLOR_LIFE      = "16A34A";   // 绿色 - 生活用品
    private static final String COLOR_SPORT     = "DC2626";   // 红色 - 运动户外
    private static final String COLOR_FURNITURE = "8B4513";   // 棕色 - 二手家具
    private static final String COLOR_BEAUTY    = "DB2777";   // 粉色 - 美妆护肤

    private String imgUrl(String color, String text) {
        return "https://placehold.co/400x400/" + color + "/white?text=" + text;
    }

    private void addGoodsImage(Long goodsId, String url) {
        GoodsImage img = new GoodsImage();
        img.setGoodsId(goodsId);
        img.setImageUrl(url);
        goodsImageRepository.save(img);
    }

    @Override
    public void run(String... args) throws Exception {
        logger.info("开始初始化数据...");

        // 初始化用户数据
        User user1 = null;
        User user2 = null;
        if (userRepository.count() == 0) {
            user1 = new User();
            user1.setUsername("管理员");
            user1.setStuid("admin");
            user1.setPassword("admin123");
            user1 = userRepository.save(user1);
            logger.info("初始化用户：{}，ID：{}", user1.getUsername(), user1.getId());

            user2 = new User();
            user2.setUsername("测试用户");
            user2.setStuid("test");
            user2.setPassword("test123");
            user2 = userRepository.save(user2);
            logger.info("初始化用户：{}，ID：{}", user2.getUsername(), user2.getId());
        } else {
            user1 = userRepository.findByStuid("admin");
            user2 = userRepository.findByStuid("test");
        }

        // 初始化商品数据（含图片）
        if (goodsRepository.count() == 0 && user1 != null && user2 != null) {
            // ===== 电子产品 =====
            Goods g = new Goods();
            g.setTitle("苹果 iPhone 15 Pro");
            g.setCategory("电子产品");
            g.setPrice(new BigDecimal(7999));
            g.setDescription("全新 iPhone 15 Pro，搭载 A17 Pro 芯片，钛金属边框");
            g.setUserId(user1.getId());
            g = goodsRepository.save(g);
            addGoodsImage(g.getId(), imgUrl(COLOR_ELECTRONIC, "iPhone+15+Pro"));
            logger.info("初始化商品：{}", g.getTitle());

            g = new Goods();
            g.setTitle("MacBook Pro 14英寸");
            g.setCategory("电子产品");
            g.setPrice(new BigDecimal(12999));
            g.setDescription("M3 Pro 芯片，16GB 内存，512GB 存储，深空黑色");
            g.setUserId(user1.getId());
            g = goodsRepository.save(g);
            addGoodsImage(g.getId(), imgUrl(COLOR_ELECTRONIC, "MacBook+Pro"));
            logger.info("初始化商品：{}", g.getTitle());

            g = new Goods();
            g.setTitle("AirPods Pro 2");
            g.setCategory("电子产品");
            g.setPrice(new BigDecimal(1899));
            g.setDescription("主动降噪，空间音频，USB-C 充电盒");
            g.setUserId(user2.getId());
            g = goodsRepository.save(g);
            addGoodsImage(g.getId(), imgUrl(COLOR_ELECTRONIC, "AirPods+Pro+2"));
            logger.info("初始化商品：{}", g.getTitle());

            g = new Goods();
            g.setTitle("iPad Air 5");
            g.setCategory("电子产品");
            g.setPrice(new BigDecimal(3999));
            g.setDescription("M1 芯片，10.9 英寸 Liquid Retina 显示屏，深空灰色");
            g.setUserId(user1.getId());
            g = goodsRepository.save(g);
            addGoodsImage(g.getId(), imgUrl(COLOR_ELECTRONIC, "iPad+Air+5"));
            logger.info("初始化商品：{}", g.getTitle());

            g = new Goods();
            g.setTitle("Apple Watch Series 9");
            g.setCategory("电子产品");
            g.setPrice(new BigDecimal(3199));
            g.setDescription("45mm GPS 版，午夜色运动表带");
            g.setUserId(user2.getId());
            g = goodsRepository.save(g);
            addGoodsImage(g.getId(), imgUrl(COLOR_ELECTRONIC, "Apple+Watch+S9"));
            logger.info("初始化商品：{}", g.getTitle());

            g = new Goods();
            g.setTitle("华为 Mate 60 Pro");
            g.setCategory("电子产品");
            g.setPrice(new BigDecimal(6999));
            g.setDescription("麒麟 9000S 芯片，卫星通话，雅川青");
            g.setUserId(user2.getId());
            g = goodsRepository.save(g);
            addGoodsImage(g.getId(), imgUrl(COLOR_ELECTRONIC, "Mate+60+Pro"));
            logger.info("初始化商品：{}", g.getTitle());

            g = new Goods();
            g.setTitle("小米 14 Ultra");
            g.setCategory("电子产品");
            g.setPrice(new BigDecimal(6499));
            g.setDescription("徕卡专业影像，骁龙 8 Gen3，钛金属特别版");
            g.setUserId(user1.getId());
            g = goodsRepository.save(g);
            addGoodsImage(g.getId(), imgUrl(COLOR_ELECTRONIC, "Xiaomi+14+Ultra"));
            logger.info("初始化商品：{}", g.getTitle());

            g = new Goods();
            g.setTitle("索尼 WH-1000XM5 头戴式耳机");
            g.setCategory("电子产品");
            g.setPrice(new BigDecimal(2899));
            g.setDescription("行业领先的降噪，30 小时续航，黑色");
            g.setUserId(user2.getId());
            g = goodsRepository.save(g);
            addGoodsImage(g.getId(), imgUrl(COLOR_ELECTRONIC, "WH-1000XM5"));
            logger.info("初始化商品：{}", g.getTitle());

            // ===== 图书教材 =====
            g = new Goods();
            g.setTitle("高等数学（第七版）");
            g.setCategory("图书教材");
            g.setPrice(new BigDecimal(35));
            g.setDescription("同济大学数学系编，高等教育出版社，上下册");
            g.setUserId(user1.getId());
            g = goodsRepository.save(g);
            addGoodsImage(g.getId(), imgUrl(COLOR_BOOK, "高等数学"));
            logger.info("初始化商品：{}", g.getTitle());

            g = new Goods();
            g.setTitle("线性代数（第六版）");
            g.setCategory("图书教材");
            g.setPrice(new BigDecimal(25));
            g.setDescription("同济大学数学系编，高等教育出版社");
            g.setUserId(user1.getId());
            g = goodsRepository.save(g);
            addGoodsImage(g.getId(), imgUrl(COLOR_BOOK, "线性代数"));
            logger.info("初始化商品：{}", g.getTitle());

            g = new Goods();
            g.setTitle("Python 编程从入门到实践");
            g.setCategory("图书教材");
            g.setPrice(new BigDecimal(58));
            g.setDescription("Eric Matthes 著，人民邮电出版社，第三版");
            g.setUserId(user2.getId());
            g = goodsRepository.save(g);
            addGoodsImage(g.getId(), imgUrl(COLOR_BOOK, "Python编程"));
            logger.info("初始化商品：{}", g.getTitle());

            g = new Goods();
            g.setTitle("算法导论（第三版）");
            g.setCategory("图书教材");
            g.setPrice(new BigDecimal(99));
            g.setDescription("Thomas H. Cormen 著，机械工业出版社");
            g.setUserId(user2.getId());
            g = goodsRepository.save(g);
            addGoodsImage(g.getId(), imgUrl(COLOR_BOOK, "算法导论"));
            logger.info("初始化商品：{}", g.getTitle());

            g = new Goods();
            g.setTitle("大学英语综合教程");
            g.setCategory("图书教材");
            g.setPrice(new BigDecimal(42));
            g.setDescription("上海外语教育出版社，学生用书 1-4 册");
            g.setUserId(user1.getId());
            g = goodsRepository.save(g);
            addGoodsImage(g.getId(), imgUrl(COLOR_BOOK, "大学英语"));
            logger.info("初始化商品：{}", g.getTitle());

            // ===== 生活用品 =====
            g = new Goods();
            g.setTitle("小米电动牙刷 T500");
            g.setCategory("生活用品");
            g.setPrice(new BigDecimal(199));
            g.setDescription("声波震动，3 种清洁模式，磁吸收纳");
            g.setUserId(user1.getId());
            g = goodsRepository.save(g);
            addGoodsImage(g.getId(), imgUrl(COLOR_LIFE, "电动牙刷"));
            logger.info("初始化商品：{}", g.getTitle());

            g = new Goods();
            g.setTitle("无印良品 加湿器");
            g.setCategory("生活用品");
            g.setPrice(new BigDecimal(499));
            g.setDescription("超声波加湿，大容量 4L，静音设计");
            g.setUserId(user2.getId());
            g = goodsRepository.save(g);
            addGoodsImage(g.getId(), imgUrl(COLOR_LIFE, "加湿器"));
            logger.info("初始化商品：{}", g.getTitle());

            g = new Goods();
            g.setTitle("飞利浦台灯 66119");
            g.setCategory("生活用品");
            g.setPrice(new BigDecimal(399));
            g.setDescription("LED 护眼灯，四档调光，国AA级照度");
            g.setUserId(user1.getId());
            g = goodsRepository.save(g);
            addGoodsImage(g.getId(), imgUrl(COLOR_LIFE, "LED台灯"));
            logger.info("初始化商品：{}", g.getTitle());

            g = new Goods();
            g.setTitle("保温杯 500ml");
            g.setCategory("生活用品");
            g.setPrice(new BigDecimal(89));
            g.setDescription("304 不锈钢，12 小时保温，一键开盖");
            g.setUserId(user2.getId());
            g = goodsRepository.save(g);
            addGoodsImage(g.getId(), imgUrl(COLOR_LIFE, "保温杯"));
            logger.info("初始化商品：{}", g.getTitle());

            g = new Goods();
            g.setTitle("戴森吹风机 HD15");
            g.setCategory("生活用品");
            g.setPrice(new BigDecimal(2990));
            g.setDescription("Supersonic 负离子护发，高速数码马达，紫红色");
            g.setUserId(user1.getId());
            g = goodsRepository.save(g);
            addGoodsImage(g.getId(), imgUrl(COLOR_LIFE, "戴森吹风机"));
            logger.info("初始化商品：{}", g.getTitle());

            // ===== 运动户外 =====
            g = new Goods();
            g.setTitle("Nike Air Force 1");
            g.setCategory("运动户外");
            g.setPrice(new BigDecimal(799));
            g.setDescription("经典款白色低帮板鞋，42 码，95 新");
            g.setUserId(user2.getId());
            g = goodsRepository.save(g);
            addGoodsImage(g.getId(), imgUrl(COLOR_SPORT, "Nike+AF1"));
            logger.info("初始化商品：{}", g.getTitle());

            g = new Goods();
            g.setTitle("阿迪达斯 Ultraboost 跑步鞋");
            g.setCategory("运动户外");
            g.setPrice(new BigDecimal(1099));
            g.setDescription("Boost 中底科技，舒适缓震，41 码");
            g.setUserId(user1.getId());
            g = goodsRepository.save(g);
            addGoodsImage(g.getId(), imgUrl(COLOR_SPORT, "Ultraboost"));
            logger.info("初始化商品：{}", g.getTitle());

            g = new Goods();
            g.setTitle("斯伯丁篮球 74-607Y");
            g.setCategory("运动户外");
            g.setPrice(new BigDecimal(199));
            g.setDescription("7号标准篮球，PU 材质，室内外通用");
            g.setUserId(user2.getId());
            g = goodsRepository.save(g);
            addGoodsImage(g.getId(), imgUrl(COLOR_SPORT, "篮球"));
            logger.info("初始化商品：{}", g.getTitle());

            g = new Goods();
            g.setTitle("优衣库羽绒服");
            g.setCategory("运动户外");
            g.setPrice(new BigDecimal(499));
            g.setDescription("轻型薄款羽绒外套，L 码，黑色，9 成新");
            g.setUserId(user1.getId());
            g = goodsRepository.save(g);
            addGoodsImage(g.getId(), imgUrl(COLOR_SPORT, "羽绒服"));
            logger.info("初始化商品：{}", g.getTitle());

            g = new Goods();
            g.setTitle("迪卡侬双肩背包 20L");
            g.setCategory("运动户外");
            g.setPrice(new BigDecimal(129));
            g.setDescription("轻量化登山包，防水面料，多隔层设计");
            g.setUserId(user2.getId());
            g = goodsRepository.save(g);
            addGoodsImage(g.getId(), imgUrl(COLOR_SPORT, "双肩背包"));
            logger.info("初始化商品：{}", g.getTitle());

            g = new Goods();
            g.setTitle("瑜伽垫 6mm");
            g.setCategory("运动户外");
            g.setPrice(new BigDecimal(79));
            g.setDescription("TPE 材质，防滑无味，双面双色，附绑带");
            g.setUserId(user1.getId());
            g = goodsRepository.save(g);
            addGoodsImage(g.getId(), imgUrl(COLOR_SPORT, "瑜伽垫"));
            logger.info("初始化商品：{}", g.getTitle());

            // ===== 二手家具 =====
            g = new Goods();
            g.setTitle("人体工学电脑椅");
            g.setCategory("二手家具");
            g.setPrice(new BigDecimal(450));
            g.setDescription("网布透气靠背，可调节高度，腰托支撑");
            g.setUserId(user2.getId());
            g = goodsRepository.save(g);
            addGoodsImage(g.getId(), imgUrl(COLOR_FURNITURE, "电脑椅"));
            logger.info("初始化商品：{}", g.getTitle());

            g = new Goods();
            g.setTitle("升降书桌 1.4m");
            g.setCategory("二手家具");
            g.setPrice(new BigDecimal(680));
            g.setDescription("电动升降，记忆高度，橡木色桌面");
            g.setUserId(user1.getId());
            g = goodsRepository.save(g);
            addGoodsImage(g.getId(), imgUrl(COLOR_FURNITURE, "升降书桌"));
            logger.info("初始化商品：{}", g.getTitle());

            g = new Goods();
            g.setTitle("简易书架 5 层");
            g.setCategory("二手家具");
            g.setPrice(new BigDecimal(150));
            g.setDescription("钢制组装书架，承重强，白色");
            g.setUserId(user2.getId());
            g = goodsRepository.save(g);
            addGoodsImage(g.getId(), imgUrl(COLOR_FURNITURE, "书架"));
            logger.info("初始化商品：{}", g.getTitle());

            // ===== 美妆护肤 =====
            g = new Goods();
            g.setTitle("兰蔻小黑瓶精华 50ml");
            g.setCategory("美妆护肤");
            g.setPrice(new BigDecimal(680));
            g.setDescription("第二代肌底精华液，修护肌肤，全新未开封");
            g.setUserId(user1.getId());
            g = goodsRepository.save(g);
            addGoodsImage(g.getId(), imgUrl(COLOR_BEAUTY, "小黑瓶精华"));
            logger.info("初始化商品：{}", g.getTitle());

            g = new Goods();
            g.setTitle("雅诗兰黛眼霜 15ml");
            g.setCategory("美妆护肤");
            g.setPrice(new BigDecimal(450));
            g.setDescription("小棕瓶抗蓝光眼霜，淡化细纹黑眼圈");
            g.setUserId(user2.getId());
            g = goodsRepository.save(g);
            addGoodsImage(g.getId(), imgUrl(COLOR_BEAUTY, "眼霜"));
            logger.info("初始化商品：{}", g.getTitle());

            g = new Goods();
            g.setTitle("完美日记口红套装");
            g.setCategory("美妆护肤");
            g.setPrice(new BigDecimal(199));
            g.setDescription("哑光丝绒质地，5 支装礼盒，日常百搭色号");
            g.setUserId(user1.getId());
            g = goodsRepository.save(g);
            addGoodsImage(g.getId(), imgUrl(COLOR_BEAUTY, "口红套装"));
            logger.info("初始化商品：{}", g.getTitle());
        }

        logger.info("数据初始化完成！");
    }
}
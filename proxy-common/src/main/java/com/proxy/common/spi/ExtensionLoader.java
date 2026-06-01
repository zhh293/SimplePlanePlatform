package com.proxy.common.spi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 全局 SPI 扩展加载器，类似 Dubbo 的 ExtensionLoader
 * <p>
 * 核心功能：
 * <ul>
 *   <li>按名称获取扩展实现（懒加载 + 单例）</li>
 *   <li>获取默认扩展（通过 @SPI 注解指定默认值）</li>
 *   <li>获取所有激活的扩展（通过 @Activate 注解条件过滤）</li>
 *   <li>从 META-INF/proxy/{接口全限定名} 文件加载扩展类映射</li>
 * </ul>
 * </p>
 *
 * <p>SPI 配置文件格式：</p>
 * <pre>
 * # META-INF/proxy/com.proxy.common.transport.Transporter
 * netty=com.proxy.transport.netty.NettyTransporter
 * mina=com.proxy.transport.mina.MinaTransporter
 * </pre>
 *
 * @param <T> SPI 接口类型
 */
public class ExtensionLoader<T> {

    private static final Logger log = LoggerFactory.getLogger(ExtensionLoader.class);

    private static final String SPI_DIR = "META-INF/proxy/";

    /**
     * 全局缓存：接口类型 → ExtensionLoader 实例
     */
    private static final ConcurrentHashMap<Class<?>, ExtensionLoader<?>> LOADERS = new ConcurrentHashMap<>();

    /**
     * SPI 接口类型
     */
    private final Class<T> type;

    /**
     * 扩展名 → 扩展实现类 的映射（懒加载）
     */
    private final ConcurrentHashMap<String, Class<? extends T>> extensionClasses = new ConcurrentHashMap<>();

    /**
     * 扩展名 → 扩展实例 的映射（单例缓存）
     */
    private final ConcurrentHashMap<String, T> extensionInstances = new ConcurrentHashMap<>();

    /**
     * 标记是否已加载过扩展类
     */
    private volatile boolean loaded = false;

    private ExtensionLoader(Class<T> type) {
        this.type = type;
    }

    /**
     * 获取指定接口类型的 ExtensionLoader 实例
     *
     * @param type SPI 接口类型（必须标注 @SPI 注解）
     * @return ExtensionLoader 实例
     * @throws IllegalArgumentException 如果 type 为 null 或不是接口或未标注 @SPI
     */
    @SuppressWarnings("unchecked")
    public static <T> ExtensionLoader<T> getLoader(Class<T> type) {
        if (type == null) {
            throw new IllegalArgumentException("Extension type must not be null");
        }
        if (!type.isInterface()) {
            throw new IllegalArgumentException("Extension type must be an interface: " + type.getName());
        }
        if (!type.isAnnotationPresent(SPI.class)) {
            throw new IllegalArgumentException("Extension type must be annotated with @SPI: " + type.getName());
        }
        return (ExtensionLoader<T>) LOADERS.computeIfAbsent(type, ExtensionLoader::new);
    }

    /**
     * 根据名称获取扩展实现（懒加载 + 单例）
     *
     * @param name 扩展名（对应 SPI 配置文件中的 key）
     * @return 扩展实例
     * @throws IllegalArgumentException 如果找不到对应的扩展实现
     */
    public T getExtension(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Extension name must not be null or empty");
        }

        // 确保扩展类已加载
        loadExtensionClassesIfNeeded();

        return extensionInstances.computeIfAbsent(name, k -> {
            Class<? extends T> clazz = extensionClasses.get(k);
            if (clazz == null) {
                throw new IllegalArgumentException(
                        "No extension '" + k + "' for type: " + type.getName()
                                + ". Available extensions: " + extensionClasses.keySet());
            }
            try {
                return clazz.getDeclaredConstructor().newInstance();
            } catch (Exception e) {
                throw new RuntimeException(
                        "Failed to create extension instance: " + clazz.getName(), e);
            }
        });
    }

    /**
     * 获取默认扩展（通过 @SPI 注解的 value() 指定默认扩展名）
     *
     * @return 默认扩展实例
     * @throws IllegalStateException 如果未指定默认扩展名
     */
    public T getDefaultExtension() {
        SPI spi = type.getAnnotation(SPI.class);
        if (spi == null || spi.value().isEmpty()) {
            throw new IllegalStateException(
                    "No default extension specified for: " + type.getName()
                            + ". Please set @SPI(\"defaultName\") on the interface.");
        }
        return getExtension(spi.value());
    }

    /**
     * 获取所有已激活的扩展实例（通过 @Activate 注解过滤）
     *
     * @param group 激活分组（为 null 时返回所有）
     * @return 已激活的扩展实例列表，按 @Activate.order() 排序
     */
    public List<T> getActivateExtensions(String group) {
        loadExtensionClassesIfNeeded();

        List<ActivatedExtension<T>> activated = new ArrayList<>();

        for (Map.Entry<String, Class<? extends T>> entry : extensionClasses.entrySet()) {
            Class<? extends T> clazz = entry.getValue();
            Activate activate = clazz.getAnnotation(Activate.class);

            if (activate == null) {
                // 没有 @Activate 注解，默认不激活
                continue;
            }

            // 检查 group 条件
            if (group != null && activate.group().length > 0) {
                boolean matched = false;
                for (String g : activate.group()) {
                    if (g.equals(group)) {
                        matched = true;
                        break;
                    }
                }
                if (!matched) {
                    continue;
                }
            }

            T instance = getExtension(entry.getKey());
            activated.add(new ActivatedExtension<>(instance, activate.order()));
        }

        // 按 order 排序
        activated.sort(Comparator.comparingInt(ActivatedExtension::getOrder));

        return activated.stream()
                .map(ActivatedExtension::getInstance)
                .collect(Collectors.toList());
    }

    /**
     * 获取所有已激活的扩展实例（不限 group）
     */
    public List<T> getActivateExtensions() {
        return getActivateExtensions(null);
    }

    /**
     * 获取所有已注册的扩展名
     */
    public Set<String> getSupportedExtensions() {
        loadExtensionClassesIfNeeded();
        return Collections.unmodifiableSet(extensionClasses.keySet());
    }

    /**
     * 判断是否存在指定名称的扩展
     */
    public boolean hasExtension(String name) {
        loadExtensionClassesIfNeeded();
        return extensionClasses.containsKey(name);
    }

    /**
     * 懒加载扩展类映射
     */
    private void loadExtensionClassesIfNeeded() {
        if (!loaded) {
            synchronized (this) {
                if (!loaded) {
                    loadExtensionClasses();
                    loaded = true;
                }
            }
        }
    }

    /**
     * 从 META-INF/proxy/{接口全限定名} 文件加载扩展类映射
     * <p>
     * 文件格式：每行一个 key=value 对
     * key 为扩展名，value 为实现类全限定名
     * 支持 # 开头的注释行和空行
     * </p>
     */
    private void loadExtensionClasses() {
        String fileName = SPI_DIR + type.getName();
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) {
            classLoader = ExtensionLoader.class.getClassLoader();
        }

        try {
            Enumeration<URL> urls = classLoader.getResources(fileName);
            while (urls.hasMoreElements()) {
                URL url = urls.nextElement();
                loadFromUrl(url, classLoader);
            }
        } catch (Exception e) {
            log.error("Failed to load extension classes for type: {}", type.getName(), e);
        }

        if (extensionClasses.isEmpty()) {
            log.debug("No extension classes found for type: {} in {}", type.getName(), fileName);
        } else {
            log.debug("Loaded {} extension classes for type: {}: {}",
                    extensionClasses.size(), type.getName(), extensionClasses.keySet());
        }
    }

    /**
     * 从单个 URL 加载扩展类映射
     */
    @SuppressWarnings("unchecked")
    private void loadFromUrl(URL url, ClassLoader classLoader) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(url.openStream(), StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                // 去除注释
                int commentIndex = line.indexOf('#');
                if (commentIndex >= 0) {
                    line = line.substring(0, commentIndex);
                }
                line = line.trim();

                if (line.isEmpty()) {
                    continue;
                }

                // 解析 key=value
                int eqIndex = line.indexOf('=');
                if (eqIndex <= 0) {
                    log.warn("Invalid SPI config line: '{}' in {}", line, url);
                    continue;
                }

                String name = line.substring(0, eqIndex).trim();
                String className = line.substring(eqIndex + 1).trim();

                if (name.isEmpty() || className.isEmpty()) {
                    log.warn("Invalid SPI config line: '{}' in {}", line, url);
                    continue;
                }

                try {
                    Class<?> clazz = Class.forName(className, true, classLoader);
                    if (!type.isAssignableFrom(clazz)) {
                        throw new IllegalStateException(
                                "Extension class " + className + " does not implement " + type.getName());
                    }
                    extensionClasses.put(name, (Class<? extends T>) clazz);
                } catch (ClassNotFoundException e) {
                    log.warn("Extension class not found: {} for name '{}' in {}",
                            className, name, url);
                }
            }
        } catch (Exception e) {
            log.error("Failed to load extension from: {}", url, e);
        }
    }

    /**
     * 重置加载器（主要用于测试）
     */
    public static void resetAll() {
        LOADERS.clear();
    }

    /**
     * 内部辅助类：带排序值的扩展实例
     */
    private static class ActivatedExtension<T> {
        private final T instance;
        private final int order;

        ActivatedExtension(T instance, int order) {
            this.instance = instance;
            this.order = order;
        }

        T getInstance() {
            return instance;
        }

        int getOrder() {
            return order;
        }
    }
}

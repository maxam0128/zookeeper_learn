# SpringBoot

## 1、SpringBoot 启动入口

在main函数中实例化一个SpringApplication对象，通过调用SpringApplication#run方法实现。代码如下：
```
public static void main(String[] args) {
    SpringApplication app = new SpringApplication(SpringBootDemoStarter.class);
    AnsiOutput.setEnabled(AnsiOutput.Enabled.ALWAYS);
    app.setBannerMode(Banner.Mode.CONSOLE);
    app.run(args);
}
```

## 2、SpringApplication 实例化

SpringApplication 实例化主要是执行 initialize 方法，下面我们看看这个初始化方法主要实现。

```
public SpringApplication(Object... sources) {
    initialize(sources);
}

private void initialize(Object[] sources) {
    if (sources != null && sources.length > 0) {
        this.sources.addAll(Arrays.asList(sources));
    }
    
    // 推断web环境，这部分实现如下：
    // 尝试加载下面两个web相关的类，如果加载成功就是web环境 String[] WEB_ENVIRONMENT_CLASSES = { "javax.servlet.Servlet",
    //   			"org.springframework.web.context.ConfigurableWebApplicationContext" }
    this.webEnvironment = deduceWebEnvironment();
    
    // 在class path 下面查找(META-INF/spring.factories)配置中的 ApplicationContextInitializer 
    setInitializers((Collection) getSpringFactoriesInstances(
            ApplicationContextInitializer.class));
    // 同样查找 ApplicationListener       
    setListeners((Collection) getSpringFactoriesInstances(ApplicationListener.class));
    
    // 找出main方法所在的类，通过当前线程栈信息去找main方法。
    this.mainApplicationClass = deduceMainApplicationClass();
}
```

## 3、SpringApplication#run 实现

```
public ConfigurableApplicationContext run(String... args) {
    
    // 应用 停止的一个观察器，主要是记录应用运行时间和任务的运行时间
    StopWatch stopWatch = new StopWatch();
    stopWatch.start();
    ConfigurableApplicationContext context = null;
    FailureAnalyzers analyzers = null;
    
    // 配置 java.awt.headless 属性
    configureHeadlessProperty();
    
    // 应用运行时的监听器
    SpringApplicationRunListeners listeners = getRunListeners(args);
    listeners.starting();
    try {
    
        // 参数对象
        ApplicationArguments applicationArguments = new DefaultApplicationArguments(
                args);
        
        // 应用配置环境        
        ConfigurableEnvironment environment = prepareEnvironment(listeners,
                applicationArguments);
        Banner printedBanner = printBanner(environment);
        
        // 创建应用上下文环境
        context = createApplicationContext();
        analyzers = new FailureAnalyzers(context);
        
        // 准备容器启动环境
        prepareContext(context, environment, listeners, applicationArguments,
                printedBanner);
                
        // 刷新应用上下文容器，这个方法最终会调用到AbstractApplicationContext#refresh 方法
        refreshContext(context);
        afterRefresh(context, applicationArguments);
        listeners.finished(context, null);
        stopWatch.stop();
        if (this.logStartupInfo) {
            new StartupInfoLogger(this.mainApplicationClass)
                    .logStarted(getApplicationLog(), stopWatch);
        }
        return context;
    }
    catch (Throwable ex) {
        handleRunFailure(context, listeners, analyzers, ex);
        throw new IllegalStateException(ex);
    }
}
```

## 4、SpringApplicationRunListeners 

### 4.1、获取 SpringApplicationRunListener

1、SpringApplication#getRunListeners
```
private SpringApplicationRunListeners getRunListeners(String[] args) {
    Class<?>[] types = new Class<?>[] { SpringApplication.class, String[].class };
    
    // 通过 getSpringFactoriesInstances 获取 SpringApplicationRunListener 实例 
    return new SpringApplicationRunListeners(logger, getSpringFactoriesInstances(
            SpringApplicationRunListener.class, types, this, args));
}
```

2、查找并实例化工厂类

在当前类路径下查找所有的 META-INF/spring.factories 文件，并且找出对应配置类 type，然后实例化。

```
private <T> Collection<? extends T> getSpringFactoriesInstances(Class<T> type,
        Class<?>[] parameterTypes, Object... args) {
    ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
    // Use names and ensure unique to protect against duplicates
    // 从 META-INF/spring.factories 文件中获取所有配置的 class name 
    Set<String> names = new LinkedHashSet<String>(
            SpringFactoriesLoader.loadFactoryNames(type, classLoader));
            
    // 实例化配种的的工厂类            
    List<T> instances = createSpringFactoriesInstances(type, parameterTypes,
            classLoader, args, names);
    AnnotationAwareOrderComparator.sort(instances);
    return instances;
}
```

3、SpringFactoriesLoader.loadFactoryNames

这个静态方法会查找当前类路径下所有spring.factories中配置的name为 SpringApplicationRunListener 工厂类。

```
// FACTORIES_RESOURCE_LOCATION = "META-INF/spring.factories"
public static List<String> loadFactoryNames(Class<?> factoryClass, ClassLoader classLoader) {
    String factoryClassName = factoryClass.getName();
    try {
        Enumeration<URL> urls = (classLoader != null ? classLoader.getResources(FACTORIES_RESOURCE_LOCATION) :
                ClassLoader.getSystemResources(FACTORIES_RESOURCE_LOCATION));
        List<String> result = new ArrayList<String>();
        while (urls.hasMoreElements()) {
            URL url = urls.nextElement();
            Properties properties = PropertiesLoaderUtils.loadProperties(new UrlResource(url));
            String factoryClassNames = properties.getProperty(factoryClassName);
            result.addAll(Arrays.asList(StringUtils.commaDelimitedListToStringArray(factoryClassNames)));
        }
        return result;
    }
    catch (IOException ex) {
        throw new IllegalArgumentException("Unable to load [" + factoryClass.getName() +
                "] factories from location [" + FACTORIES_RESOURCE_LOCATION + "]", ex);
    }
}
```
上面通过一系列方法会查找出整个应用工程包括jar包中(META-INF/spring.factories)中配置的SpringApplicationRunListener，然后实例化。
那么这个接口类究竟是干什么的呢，下面我们看下它的接口方法。

```
public interface SpringApplicationRunListener {

	/**
	 * Called immediately when the run method has first started. Can be used for very
	 * early initialization.
	 * 方法是在SpringApplication#run方法启动后立刻调用，可以用于早期的初始化
	 */
	void starting();

	/**
	 * Called once the environment has been prepared, but before the
	 * {@link ApplicationContext} has been created.
	 * 在 ApplicationContext 创建之前，environment 准备之后调用
	 * @param environment the environment
	 */
	void environmentPrepared(ConfigurableEnvironment environment);

	/**
	 * Called once the {@link ApplicationContext} has been created and prepared, but
	 * before sources have been loaded.
	 * 在 source load 之前，ApplicationContext 创建和准备之后调用
	 * @param context the application context
	 */
	void contextPrepared(ConfigurableApplicationContext context);

	/**
	 * Called once the application context has been loaded but before it has been
	 * refreshed.
	 * 在 application context load之后，调用 refresh 之前
	 * @param context the application context
	 */
	void contextLoaded(ConfigurableApplicationContext context);

	/**
	 * Called immediately before the run method finishes.
	 * @param context the application context or null if a failure occurred before the
	 * context was created
	 * 容器刷新完成之后调用
	 * @param exception any run exception or null if run completed successfully.
	 */
	void finished(ConfigurableApplicationContext context, Throwable exception);

}
```

从SpringApplicationRunListener 的接口方法可以看出，这是SpringBoot启动过程在一些重要节点预留的方法，也是SpringBoot启动过程中预留的扩展点。
在SpringBoot中默认的实现是 EventPublishingRunListener，这个实现类会在启动时的相应节点中通过SimpleApplicationEventMulticaster 发布相应的事件。

### 4.2、发布 ApplicationStartedEvent 应用启动事件

这部分看下ApplicationStartedEvent 事件发布，我们从 EventPublishingRunListener#starting 开始看起。

```
private final SimpleApplicationEventMulticaster initialMulticaster;

@Override
@SuppressWarnings("deprecation")
public void starting() {
    this.initialMulticaster
            .multicastEvent(new ApplicationStartedEvent(this.application, this.args));
}
```

我们可以看到，事件的发布最终会委托给SimpleApplicationEventMulticaster 事件发布器去做，这个方法就是在这里会实例化一个ApplicationStartedEvent事件。

```
@Override

public void multicastEvent(ApplicationEvent event) {
    // 主要是对应用事件进行包装
    multicastEvent(event, resolveDefaultEventType(event));
}


// 事件广播了，可以看出下面有一个getApplicationListeners，这里会获取所有观察者。
// 如果配置了线程池，就异步去广播，没有配置的话就同步调用
@Override
public void multicastEvent(final ApplicationEvent event, ResolvableType eventType) {
    ResolvableType type = (eventType != null ? eventType : resolveDefaultEventType(event));
    for (final ApplicationListener<?> listener : getApplicationListeners(event, type)) {
        Executor executor = getTaskExecutor();
        if (executor != null) {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    invokeListener(listener, event);
                }
            });
        }
        else {
            // 调用 ApplicationListener#onApplicationEvent 完成事件发布
            invokeListener(listener, event);
        }
    }
}
```

下面我们看下 getApplicationListeners 的实现。

```

protected Collection<ApplicationListener<?>> getApplicationListeners(
			ApplicationEvent event, ResolvableType eventType) {

    Object source = event.getSource();
    Class<?> sourceType = (source != null ? source.getClass() : null);
    
    // 事件缓存的key
    ListenerCacheKey cacheKey = new ListenerCacheKey(eventType, sourceType);

    // Quick check for existing entry on ConcurrentHashMap...
    // 会将事件的观察者做缓存
    ListenerRetriever retriever = this.retrieverCache.get(cacheKey);
    if (retriever != null) {
        return retriever.getApplicationListeners();
    }

    if (this.beanClassLoader == null ||
            (ClassUtils.isCacheSafe(event.getClass(), this.beanClassLoader) &&
                    (sourceType == null || ClassUtils.isCacheSafe(sourceType, this.beanClassLoader)))) {
        // Fully synchronized building and caching of a ListenerRetriever
        synchronized (this.retrievalMutex) {
            retriever = this.retrieverCache.get(cacheKey);
            if (retriever != null) {
                return retriever.getApplicationListeners();
            }
            retriever = new ListenerRetriever(true);
            
            // 应用监听器检索对应的观察者，并做缓存
            Collection<ApplicationListener<?>> listeners =
                    retrieveApplicationListeners(eventType, sourceType, retriever);
            this.retrieverCache.put(cacheKey, retriever);
            return listeners;
        }
    }
    else {
        // No ListenerRetriever caching -> no synchronization necessary
        return retrieveApplicationListeners(eventType, sourceType, null);
    }
}

```

事件类型检索

```
private Collection<ApplicationListener<?>> retrieveApplicationListeners(
        ResolvableType eventType, Class<?> sourceType, ListenerRetriever retriever) {

    // 应用监听器
    LinkedList<ApplicationListener<?>> allListeners = new LinkedList<ApplicationListener<?>>();
    Set<ApplicationListener<?>> listeners;
    Set<String> listenerBeans;
    synchronized (this.retrievalMutex) {
        // 这里的监听器就是SpringApplication在初始化时从 class path 中查找到的监听器并在 EventPublishingRunListener 实例化的时候
        // 传给 SimpleApplicationEventMulticaster 事件多播器的，详情参考 EventPublishingRunListener 的实例化
        listeners = new LinkedHashSet<ApplicationListener<?>>(this.defaultRetriever.applicationListeners);
        listenerBeans = new LinkedHashSet<String>(this.defaultRetriever.applicationListenerBeans);
    }
    
    // 这里会过滤出事件的观察者
    for (ApplicationListener<?> listener : listeners) {
        if (supportsEvent(listener, eventType, sourceType)) {
            if (retriever != null) {
                retriever.applicationListeners.add(listener);
            }
            allListeners.add(listener);
        }
    }
    
    // 实例化listenerBean 
    if (!listenerBeans.isEmpty()) {
        BeanFactory beanFactory = getBeanFactory();
        for (String listenerBeanName : listenerBeans) {
            try {
                Class<?> listenerType = beanFactory.getType(listenerBeanName);
                if (listenerType == null || supportsEvent(listenerType, eventType)) {
                    ApplicationListener<?> listener =
                            beanFactory.getBean(listenerBeanName, ApplicationListener.class);
                    if (!allListeners.contains(listener) && supportsEvent(listener, eventType, sourceType)) {
                        if (retriever != null) {
                            retriever.applicationListenerBeans.add(listenerBeanName);
                        }
                        allListeners.add(listener);
                    }
                }
            }
            catch (NoSuchBeanDefinitionException ex) {
                // Singleton listener instance (without backing bean definition) disappeared -
                // probably in the middle of the destruction phase
            }
        }
    }
    AnnotationAwareOrderComparator.sort(allListeners);
    return allListeners;
}
```
最后，将前面检索出来的事件观察者(ApplicationListener)，然后通过调用 ApplicationListener#onApplicationEvent 完成时间的发布。
下面我们看我们应用中实际用到的一个例子-UccConfigLoaderListener。

Example: UccConfigLoaderListener
```

// 这个类实现的主要功能就是在应用启动的时候从disconf 服务端拉取配置数据
public class UccConfigLoaderListener implements ApplicationListener<ApplicationStartingEvent> {

    private static final Logger logger = LoggerFactory.getLogger(UccConfigLoaderListener.class);

    @Override
    public void onApplicationEvent(ApplicationStartingEvent event) {
        
        // 实例化静态配置熟悉的 factoryBean
        StaticConfigPropertiesFactoryBean factoryBean = new StaticConfigPropertiesFactoryBean();
        
        // 设置属性资源的name
        factoryBean.setResourceNameList(Lists.newArrayList("application"));
        try {
            
            factoryBean.afterPropertiesSet();
            factoryBean.getObject();
        } catch (IOException e) {
            logger.error("UccConfigLoaderListener init error!", e);
            throw new IllegalStateException(e);
        }
    }

}
```
## 5、设置ApplicationArguments

根据系统启动参数构造 DefaultApplicationArguments 。

## 6、准备应用启动环境 prepareEnvironment 

主要还是SpringApplication#prepareEnvironment。

```

// 这个方法两个参数
// SpringApplicationRunListeners，第4步中实例化的 EventPublishingRunListener
// ApplicationArguments：第五步构造的应用参数
private ConfigurableEnvironment prepareEnvironment(
        SpringApplicationRunListeners listeners,
        ApplicationArguments applicationArguments) {
    // Create and configure the environment
    // 创建环境
    ConfigurableEnvironment environment = getOrCreateEnvironment();
    
    // 根据应用参数配置环境
    configureEnvironment(environment, applicationArguments.getSourceArgs());
    
    // 事件发布器发布 environmentPrepared 事件
    listeners.environmentPrepared(environment);
    if (!this.webEnvironment) {
        environment = new EnvironmentConverter(getClassLoader())
                .convertToStandardEnvironmentIfNecessary(environment);
    }
    return environment;
}
```

### 6.1、创建配置环境

这里会根据当前环境是否为web创建对应的环境。web下为：StandardServletEnvironment；非web为：StandardEnvironment。

```
private ConfigurableEnvironment getOrCreateEnvironment() {
    if (this.environment != null) {
        return this.environment;
    }
    
    // webEnvironment SpringApplication 初始化是推断是否为web环境
    if (this.webEnvironment) {
        return new StandardServletEnvironment();
    }
    return new StandardEnvironment();
}
```

不难知道，StandardServletEnvironment 是 StandardEnvironment 的一个扩展，StandardServletEnvironment实现了ConfigurableWebEnvironment 接口，
进而实现了 对web上下文的一些环境配置。下面代码为扩展的方法实现。

```
@Override
public void initPropertySources(ServletContext servletContext, ServletConfig servletConfig) {

    // 初始化 servlet属性资源配置
    WebApplicationContextUtils.initServletPropertySources(getPropertySources(), servletContext, servletConfig);
}    
```

### 6.2、配置环境

配置环境主要是属性资源的配置和profile的配置，代码如下：

```
protected void configureEnvironment(ConfigurableEnvironment environment,
        String[] args) {
    
    // 属性资源配置
    configurePropertySources(environment, args);
    // profile 配置
    configureProfiles(environment, args);
}
```

下面我们看下对于profile的配置。

```
protected void configureProfiles(ConfigurableEnvironment environment, String[] args) {

    // 获取profile
    // 如果没有配置，则会从属性文件中读取 String ACTIVE_PROFILES_PROPERTY_NAME = "spring.profiles.active";
    environment.getActiveProfiles(); // ensure they are initialized
    // But these ones should go first (last wins in a property key clash)
    Set<String> profiles = new LinkedHashSet<String>(this.additionalProfiles);
    profiles.addAll(Arrays.asList(environment.getActiveProfiles()));
    
    // 设置环境 profile
    environment.setActiveProfiles(profiles.toArray(new String[profiles.size()]));
}
```

## 7、printBanner 

打印环境中配置的Banner

## 8、创建应用上下文环境

web环境下为AnnotationConfigEmbeddedWebApplicationContext;非web环境为AnnotationConfigApplicationContext。代码如下：
```
protected ConfigurableApplicationContext createApplicationContext() {
    Class<?> contextClass = this.applicationContextClass;
    if (contextClass == null) {
        try {
            // String DEFAULT_WEB_CONTEXT_CLASS = "org.springframework. boot.context.embedded.AnnotationConfigEmbeddedWebApplicationContext"
        	// String DEFAULT_CONTEXT_CLASS = "org.springframework.context.annotation.AnnotationConfigApplicationContext"		
            contextClass = Class.forName(this.webEnvironment
                    ? DEFAULT_WEB_CONTEXT_CLASS : DEFAULT_CONTEXT_CLASS);
        }
        catch (ClassNotFoundException ex) {
            throw new IllegalStateException(
                    "Unable create a default ApplicationContext, "
                            + "please specify an ApplicationContextClass",
                    ex);
        }
    }
    return (ConfigurableApplicationContext) BeanUtils.instantiate(contextClass);
}
```

## 9、准备上下文环境

代码如下： 
```
private void prepareContext(ConfigurableApplicationContext context,
        ConfigurableEnvironment environment, SpringApplicationRunListeners listeners,
        ApplicationArguments applicationArguments, Banner printedBanner) {
        
    // 设置环境
    context.setEnvironment(environment);
    
    // 
    postProcessApplicationContext(context);
    applyInitializers(context);
    
    // 发布 contextPrepared 事件
    listeners.contextPrepared(context);
    if (this.logStartupInfo) {
        logStartupInfo(context.getParent() == null);
        logStartupProfileInfo(context);
    }

    // Add boot specific singleton beans
    context.getBeanFactory().registerSingleton("springApplicationArguments",
            applicationArguments);
    if (printedBanner != null) {
        context.getBeanFactory().registerSingleton("springBootBanner", printedBanner);
    }

    // Load the sources
    Set<Object> sources = getSources();
    Assert.notEmpty(sources, "Sources must not be empty");
    
    // 
    load(context, sources.toArray(new Object[sources.size()]));
    listeners.contextLoaded(context);
}
```

### 9.1、设置配置环境

```
@Override
public void setEnvironment(ConfigurableEnvironment environment) {
    super.setEnvironment(environment);
    
    // 设置reader 和 scanner的配置环境
    this.reader.setEnvironment(environment);
    this.scanner.setEnvironment(environment);
}

// reader 和 scanner的 初始化见 AnnotationConfigEmbeddedWebApplicationContext 
// 构造函数中初始化了默认的 reader 和 scanner
public AnnotationConfigEmbeddedWebApplicationContext() {
    this.reader = new AnnotatedBeanDefinitionReader(this);
    this.scanner = new ClassPathBeanDefinitionScanner(this);
}
```

### 9.2、处理应用上下文

如果beanNameGenerator、resourceLoader(包含 ClassLoader)不为空的话，将它们配置的beanFactory中
```
protected void postProcessApplicationContext(ConfigurableApplicationContext context) {
    if (this.beanNameGenerator != null) {
        context.getBeanFactory().registerSingleton(
                AnnotationConfigUtils.CONFIGURATION_BEAN_NAME_GENERATOR,
                this.beanNameGenerator);
    }
    if (this.resourceLoader != null) {
        if (context instanceof GenericApplicationContext) {
            ((GenericApplicationContext) context)
                    .setResourceLoader(this.resourceLoader);
        }
        if (context instanceof DefaultResourceLoader) {
            ((DefaultResourceLoader) context)
                    .setClassLoader(this.resourceLoader.getClassLoader());
        }
    }
}
```

### 9.3、调用初始器

在容器 refresh前先调用初始化处理器。getInitializers 这个是在SpringApplication 初始化是从 spring.factories 中获取到的信息。
```
protected void applyInitializers(ConfigurableApplicationContext context) {
    for (ApplicationContextInitializer initializer : getInitializers()) {
        Class<?> requiredType = GenericTypeResolver.resolveTypeArgument(
                initializer.getClass(), ApplicationContextInitializer.class);
        Assert.isInstanceOf(requiredType, context, "Unable to call initializer.");
        initializer.initialize(context);
    }
}
```

### 9.4、load Bean info

这里主要是初始化 load bean 所需的一些配置，然后load bean。

```
protected void load(ApplicationContext context, Object[] sources) {
    if (logger.isDebugEnabled()) {
        logger.debug(
                "Loading source " + StringUtils.arrayToCommaDelimitedString(sources));
    }
    
    // 创建 BeanDefinitionLoader 
    BeanDefinitionLoader loader = createBeanDefinitionLoader(
            getBeanDefinitionRegistry(context), sources);
    
    if (this.beanNameGenerator != null) {
        loader.setBeanNameGenerator(this.beanNameGenerator);
    }
    
    if (this.resourceLoader != null) {
        loader.setResourceLoader(this.resourceLoader);
    }
    if (this.environment != null) {
        loader.setEnvironment(this.environment);
    }
    
    // 
    loader.load();
}

```

#### 9.4.1、createBeanDefinitionLoader

可以看出BeanDefinitionLoader 主要包括了 AnnotatedBeanDefinitionReader、XmlBeanDefinitionReader、ClassPathBeanDefinitionScanner和ClassExcludeFilter等，为load bean做准备工作。
```
BeanDefinitionLoader(BeanDefinitionRegistry registry, Object... sources) {
    Assert.notNull(registry, "Registry must not be null");
    Assert.notEmpty(sources, "Sources must not be empty");
    this.sources = sources;
    this.annotatedReader = new AnnotatedBeanDefinitionReader(registry);
    this.xmlReader = new XmlBeanDefinitionReader(registry);
    if (isGroovyPresent()) {
        this.groovyReader = new GroovyBeanDefinitionReader(registry);
    }
    this.scanner = new ClassPathBeanDefinitionScanner(registry);
    this.scanner.addExcludeFilter(new ClassExcludeFilter(sources));
}
```
#### 9.4.2、BeanDefinitionLoader#load

```
private int load(Class<?> source) {
    if (isGroovyPresent()) {
        // Any GroovyLoaders added in beans{} DSL can contribute beans here
        if (GroovyBeanDefinitionSource.class.isAssignableFrom(source)) {
            GroovyBeanDefinitionSource loader = BeanUtils.instantiateClass(source,
                    GroovyBeanDefinitionSource.class);
            load(loader);
        }
    }
    
    // 只扫描 component 组件的资源，在Springboot启动时，只初始化main 方法所在的类。
    if (isComponent(source)) {
        
        // 参考 AnnotatedBeanDefinitionReader#registerBean
        this.annotatedReader.register(source);
        return 1;
    }
    return 0;
}
```

对于AnnotatedBeanDefinitionReader#registerBean 方法的具体实现，这里就不展开分析了。
下面我们看着BeanDefinitionLoader#ConditionEvaluator#shouldSkip方法的实现，此方法主要是处理条件注解的过程，代码如下：

ConditionEvaluator#shouldSkip
```
public boolean shouldSkip(AnnotatedTypeMetadata metadata, ConfigurationPhase phase) {
    
    // 首先会判断当前要加载的类是否有Conditional注解，没有的话不会被skip
    if (metadata == null || !metadata.isAnnotated(Conditional.class.getName())) {
        return false;
    }
    
    // 配置阶段为空的话，判断当前配置类型，
    if (phase == null) {
        if (metadata instanceof AnnotationMetadata &&
                
                // 是否为配置类
                ConfigurationClassUtils.isConfigurationCandidate((AnnotationMetadata) metadata)) {
                // 配置bean
            return shouldSkip(metadata, ConfigurationPhase.PARSE_CONFIGURATION);
        }
        // 正常的bean
        return shouldSkip(metadata, ConfigurationPhase.REGISTER_BEAN);
    }

    List<Condition> conditions = new ArrayList<Condition>();
    
    // 解析配置类的条件集合，获取配置类上条件注解上的数据
    for (String[] conditionClasses : getConditionClasses(metadata)) {
        for (String conditionClass : conditionClasses) {
            Condition condition = getCondition(conditionClass, this.context.getClassLoader());
            conditions.add(condition);
        }
    }

    AnnotationAwareOrderComparator.sort(conditions);

    for (Condition condition : conditions) {
        ConfigurationPhase requiredPhase = null;
        if (condition instanceof ConfigurationCondition) {
            requiredPhase = ((ConfigurationCondition) condition).getConfigurationPhase();
        }
        if (requiredPhase == null || requiredPhase == phase) {
            
            // 判断条件是否满足
            if (!condition.matches(this.context, metadata)) {
                return true;
            }
        }
    }

    return false;
}
```

查找当前类中所有包含 Conditional 注解的属性
```
private List<String[]> getConditionClasses(AnnotatedTypeMetadata metadata) {
    MultiValueMap<String, Object> attributes = metadata.getAllAnnotationAttributes(Conditional.class.getName(), true);
    Object values = (attributes != null ? attributes.get("value") : null);
    return (List<String[]>) (values != null ? values : Collections.emptyList());
}
```

### 9.5、发布contextLoaded事件

```
@Override
public void contextLoaded(ConfigurableApplicationContext context) {
    for (ApplicationListener<?> listener : this.application.getListeners()) {
        
        // 如果 listener 实现了 ApplicationContextAware 接口，则设置 ConfigurableApplicationContext
        if (listener instanceof ApplicationContextAware) {
            ((ApplicationContextAware) listener).setApplicationContext(context);
        }
        context.addApplicationListener(listener);
    }
    
    // 发布 ApplicationPreparedEvent 事件
    this.initialMulticaster.multicastEvent(
            new ApplicationPreparedEvent(this.application, this.args, context));
}
```

## 10、刷新上下文 

这部分主要是通过 AbstractApplicationContext#refresh 实现上下文刷新，包括bean的加载、依赖注入、启动Servlet 容器等，代码如下：

```
private void refreshContext(ConfigurableApplicationContext context) {
    
    // 刷新上下文
    refresh(context);
    
    // 注册容器关闭钩子
    if (this.registerShutdownHook) {
        try {
            // AbstractApplicationContext#doClose
            context.registerShutdownHook();
        }
        catch (AccessControlException ex) {
            // Not allowed in some environments.
        }
    }
}
```

### 10.1、容器关闭

```
protected void doClose() {
    
    // 设置关闭标志位
    if (this.active.get() && this.closed.compareAndSet(false, true)) {
        if (logger.isInfoEnabled()) {
            logger.info("Closing " + this);
        }
        
        // 取消注册的JMX MBean
        LiveBeansView.unregisterApplicationContext(this);

        try {
            // 发布上下文关闭事件，这个事件可能不会被处理
            publishEvent(new ContextClosedEvent(this));
        }
        catch (Throwable ex) {
            logger.warn("Exception thrown from ApplicationListener handling ContextClosedEvent", ex);
        }

        try {
            
            // 停止所有Lifecycle bean
            getLifecycleProcessor().onClose();
        }
        catch (Throwable ex) {
            logger.warn("Exception thrown from LifecycleProcessor on context close", ex);
        }

        // Destroy all cached singletons in the context's BeanFactory.
        // 销毁上下文环境 BeanFactory 中所有的缓存单例bean 
        // 这里会调用实现了DisposableBean#destroy
        // 销毁也是先销毁当前bean的依赖，在销毁当前bean
        destroyBeans();

        // 关闭 BeanFactory
        closeBeanFactory();

        onClose();

        this.active.set(false);
    }
}
```




## 11、刷新完成


```
protected void afterRefresh(ConfigurableApplicationContext context,
        ApplicationArguments args) {
    callRunners(context, args);
}

private void callRunners(ApplicationContext context, ApplicationArguments args) {
    List<Object> runners = new ArrayList<Object>();
    
    // 获取所有的 ApplicationRunner 和 CommandLineRunner 类型的bean
    runners.addAll(context.getBeansOfType(ApplicationRunner.class).values());
    runners.addAll(context.getBeansOfType(CommandLineRunner.class).values());
    AnnotationAwareOrderComparator.sort(runners);
    
    // 依次调用对应的方法，两个种类型的bean可以在容器刷新完后立即执行，可以当做某种任务使用吧
    for (Object runner : new LinkedHashSet<Object>(runners)) {
        if (runner instanceof ApplicationRunner) {
            callRunner((ApplicationRunner) runner, args);
        }
        if (runner instanceof CommandLineRunner) {
            callRunner((CommandLineRunner) runner, args);
        }
    }
}
```

## 12、发布 应用结束事件 

从下面代码可以看出，根据容器启动过程中是否发生异常来选择发布对应的事件。

```
private SpringApplicationEvent getFinishedEvent(
        ConfigurableApplicationContext context, Throwable exception) {
    if (exception != null) {
        return new ApplicationFailedEvent(this.application, this.args, context,
                exception);
    }
    return new ApplicationReadyEvent(this.application, this.args, context);
}
```





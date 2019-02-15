
# ConfigurationClassParser 

SpringBoot 配置类解析是通过 ConfigurationClassParser 实现，调用链路可以参考下面所示：

```
PostProcessorRegistrationDelegate#invokeBeanFactoryPostProcessors
    -> PostProcessorRegistrationDelegate#invokeBeanDefinitionRegistryPostProcessors
        -> ConfigurationClassPostProcessor#postProcessBeanDefinitionRegistry
            -> ConfigurationClassPostProcessor#processConfigBeanDefinitions
```

下面我们看下 processConfigBeanDefinitions 这个方法的实现。

## processConfigBeanDefinitions

这个方法的主要功能是从 BeanDefinitions容器(BeanDefinitionRegistry -> DefaultListableBeanFactory)中找出配置类，
然后通过ConfigurationClassParser解析，当然在SpringBoot下面首先会解析Starter(main 方法所在的类)类。下面我们首先看下ConfigurationClassParser的构造函数。

### ConfigurationClassParser 
```
public ConfigurationClassParser(MetadataReaderFactory metadataReaderFactory,
        ProblemReporter problemReporter, Environment environment, ResourceLoader resourceLoader,
        BeanNameGenerator componentScanBeanNameGenerator, BeanDefinitionRegistry registry) {
    
    // 元数据读取器的工厂类
    this.metadataReaderFactory = metadataReaderFactory;
    // SPI 接口，在bean 解析阶段允许外部处理器处理错误和警告信息
    this.problemReporter = problemReporter;
    // 应用上下文环境
    this.environment = environment;
    // 资源载入器
    this.resourceLoader = resourceLoader;
    // bean 注册的容器(即DefaultListableBeanFactory)
    this.registry = registry;
    // bean 扫描的解析器(会扫描所有的 Component 注解)，此外这里还有 bean name 的一个产生器
    this.componentScanParser = new ComponentScanAnnotationParser(
            environment, resourceLoader, componentScanBeanNameGenerator, registry);
    // 处理 Springboot下面 Conditional 注解
    this.conditionEvaluator = new ConditionEvaluator(registry, environment, resourceLoader);
}
```

### ConfigurationClassParser#parse

这个功能主要有两个方面：1、解析当前bean；2、处理@Import注解。
通过简单的代码实现可以得知:parse方法最终实现都是通过processConfigurationClass 来实现的，所以下面我们着重看processConfigurationClass的实现。

```
protected void processConfigurationClass(ConfigurationClass configClass) throws IOException {
    // 判断当前bean是否需要跳过
    if (this.conditionEvaluator.shouldSkip(configClass.getMetadata(), ConfigurationPhase.PARSE_CONFIGURATION)) {
        return;
    }

    // 当前配置类是否已经存在
    ConfigurationClass existingClass = this.configurationClasses.get(configClass);
    if (existingClass != null) {
        // 配置类是否通过 @Import 嵌套在其他类中被注册的 
        if (configClass.isImported()) {
            if (existingClass.isImported()) {
                existingClass.mergeImportedBy(configClass);
            }
            // Otherwise ignore new imported config class; existing non-imported class overrides it.
            return;
        }
        else {
            // Explicit bean definition found, probably replacing an import.
            // Let's remove the old one and go with the new one.
            this.configurationClasses.remove(configClass);
            for (Iterator<ConfigurationClass> it = this.knownSuperclasses.values().iterator(); it.hasNext();) {
                if (configClass.equals(it.next())) {
                    it.remove();
                }
            }
        }
    }

    // 递归处理配置类
    SourceClass sourceClass = asSourceClass(configClass);
    do {
        // 处理配置类
        sourceClass = doProcessConfigurationClass(configClass, sourceClass);
    }
    while (sourceClass != null);

    this.configurationClasses.put(configClass, configClass);
}

```

下面我们看 doProcessConfigurationClass 方法的具体实现：
```
protected final SourceClass doProcessConfigurationClass(ConfigurationClass configClass, SourceClass sourceClass)
			throws IOException {

    // 递归处理嵌套类
    processMemberClasses(configClass, sourceClass);

    // 处理当前类上的  @PropertySource 注解
    for (AnnotationAttributes propertySource : AnnotationConfigUtils.attributesForRepeatable(
            sourceClass.getMetadata(), PropertySources.class,
            org.springframework.context.annotation.PropertySource.class)) {
        if (this.environment instanceof ConfigurableEnvironment) {
            // 读取配置文件
            processPropertySource(propertySource);
        }
        else {
            logger.warn("Ignoring @PropertySource annotation on [" + sourceClass.getMetadata().getClassName() +
                    "]. Reason: Environment must implement ConfigurableEnvironment");
        }
    }

    // 处理当前配置类上 @ComponentScan 注解
    Set<AnnotationAttributes> componentScans = AnnotationConfigUtils.attributesForRepeatable(
            sourceClass.getMetadata(), ComponentScans.class, ComponentScan.class);
    if (!componentScans.isEmpty() &&
            !this.conditionEvaluator.shouldSkip(sourceClass.getMetadata(), ConfigurationPhase.REGISTER_BEAN)) {
        for (AnnotationAttributes componentScan : componentScans) {
            
            // ComponentScanAnnotationParser#parse，方法的实现后续在分析，这里说一下这个方法的主要功能
            // 会读取 componentScan 上的所有属性，例如说 lazyInit，basePackages，basePackageClasses 等等。
            // 注：如果没有配置basePackages或basePackageClasses 等，则会取当前配置类所在的包
            // 1、然后会调用ClassPathBeanDefinitionScanner#doScan 扫描当前包所在路径上的所有bean
            // 2、找出符合条件的 components(即所有的@Component注解的类) ，可以参考findCandidateComponents实现。
            // 3、将解析出来的bean 包装成BeanDefinition(ScannedGenericBeanDefinition)
            // 4、AnnotationConfigUtils#processCommonDefinitionAnnotations 设置当前bean的属性(包括Lazy、DependsOn等)
            // 5、将每个bean对应的 BeanDefinition 包装成 BeanDefinitionHolder注册到当前容器中(参考 ClassPathBeanDefinitionScanner#registerBeanDefinition，同时也会注册bean的别名)
            Set<BeanDefinitionHolder> scannedBeanDefinitions =
                    this.componentScanParser.parse(componentScan, sourceClass.getMetadata().getClassName());
            
            // 检查解析的bean中是否包含配置的bean，如果有递归的去解析
            for (BeanDefinitionHolder holder : scannedBeanDefinitions) {
                if (ConfigurationClassUtils.checkConfigurationClassCandidate(
                        holder.getBeanDefinition(), this.metadataReaderFactory)) {
                    parse(holder.getBeanDefinition().getBeanClassName(), holder.getBeanName());
                }
            }
        }
    }

    // 处理@Import注解
    // 这里会通过递归的方式处理每个bean上的@Import注解
    // 在处理Starter类时（main 方法所在的类)，如果用了@SpringBootApplication注解，那么会包含下面几个类：
    // 1、org.springframework.boot.autoconfigure.AutoConfigurationPackages$Registrar
    // 2、org.springframework.boot.autoconfigure.EnableAutoConfigurationImportSelector
    // 如果使用了缓存 即 @EnableCaching，则会有下面的类
    // 1、org.springframework.cache.annotation.CachingConfigurationSelector
    processImports(configClass, sourceClass, getImports(sourceClass), true);

    // 处理@ImportResource 注解，通过@ImportResource 引入的配置文件
    if (sourceClass.getMetadata().isAnnotated(ImportResource.class.getName())) {
        AnnotationAttributes importResource =
                AnnotationConfigUtils.attributesFor(sourceClass.getMetadata(), ImportResource.class);
        String[] resources = importResource.getStringArray("locations");
        Class<? extends BeanDefinitionReader> readerClass = importResource.getClass("reader");
        for (String resource : resources) {
            // 解析占位符
            // 通过ConfigurablePropertyResolver#resolveRequiredPlaceholders 实现
            
            String resolvedResource = this.environment.resolveRequiredPlaceholders(resource);
            configClass.addImportedResource(resolvedResource, readerClass);
        }
    }

    // Process individual @Bean methods
    // 处理当前配置类中所有@Bean 的方法
    Set<MethodMetadata> beanMethods = retrieveBeanMethodMetadata(sourceClass);
    for (MethodMetadata methodMetadata : beanMethods) {
        configClass.addBeanMethod(new BeanMethod(methodMetadata, configClass));
    }

    // Process default methods on interfaces
    // 如果当前bean是一个接口，则处理接口中的默认方法
    processInterfaces(configClass, sourceClass);

    // 处理父类
    if (sourceClass.getMetadata().hasSuperClass()) {
        String superclass = sourceClass.getMetadata().getSuperClassName();
        if (!superclass.startsWith("java") && !this.knownSuperclasses.containsKey(superclass)) {
            this.knownSuperclasses.put(superclass, configClass);
            // Superclass found, return its annotation metadata and recurse
            return sourceClass.getSuperClass();
        }
    }

    // No superclass -> processing is complete
    return null;
}
```

到上面为止，配置类的解析就已经完成了，下面我们看看对于ImportSelector 的处理，方法为：processDeferredImportSelectors。


#### processDeferredImportSelectors


```
private void processDeferredImportSelectors() {
     
    List<DeferredImportSelectorHolder> deferredImports = this.deferredImportSelectors;
    this.deferredImportSelectors = null;
    Collections.sort(deferredImports, DEFERRED_IMPORT_COMPARATOR);

    for (DeferredImportSelectorHolder deferredImport : deferredImports) {
        ConfigurationClass configClass = deferredImport.getConfigurationClass();
        try {
            // 主要参考 SpringBoot 下面主要为 EnableAutoConfigurationImportSelector
            // 可以看出通过 AutoConfigurationImportSelector#selectImports方法 会将 "META-INF/spring-autoconfigure-metadata.properties"自动配置属性文件解析为PropertiesAutoConfigurationMetadata
            // 然后会通过getCandidateConfigurations 方法找出所有有效的AutoConfig配置类(加载jar中 META-INF/spring.factories 配置的类)
            // 通过 AutoConfigurationImportSelector#fireAutoConfigurationImportEvents 发布自动配置的事件
            // String[] imports = deferredImport.getImportSelector().selectImports(configClass.getMetadata());
            // 接下来以递归的方式处理上一个步骤中得到的import 类
            processImports(configClass, asSourceClass(configClass), asSourceClasses(imports), false);
        }
        catch (BeanDefinitionStoreException ex) {
            throw ex;
        }
        catch (Throwable ex) {
            throw new BeanDefinitionStoreException(
                    "Failed to process import candidates for configuration class [" +
                    configClass.getMetadata().getClassName() + "]", ex);
        }
    }
}
```

 
#### ConditionEvaluator 
 
#### ConditionEvaluator#shouldSkip

```
public boolean shouldSkip(AnnotatedTypeMetadata metadata, ConfigurationPhase phase) {
    // 判断当前bean是否有 Conditional 注解，如果没有则返回false，说明要继续解析当前的bean
    if (metadata == null || !metadata.isAnnotated(Conditional.class.getName())) {
        return false;
    }
    
    // 配置阶段，分为配置解析阶段和bean 注册阶段
    // 如果该值为空就默认
    if (phase == null) {
        
        // 当前类是否为 @Configuration 和 @Bean 类，如果是，设置配置解析阶段，否则就是bean注册阶段
        if (metadata instanceof AnnotationMetadata &&
                ConfigurationClassUtils.isConfigurationCandidate((AnnotationMetadata) metadata)) {
            return shouldSkip(metadata, ConfigurationPhase.PARSE_CONFIGURATION);
        }
        return shouldSkip(metadata, ConfigurationPhase.REGISTER_BEAN);
    }

    List<Condition> conditions = new ArrayList<Condition>();
    
    // 读取当前bean上的condition 条件
    for (String[] conditionClasses : getConditionClasses(metadata)) {
        for (String conditionClass : conditionClasses) {
            // 会实例化 Conditional 中配置的类
            // @Conditional 中配置类都是 Condition的子类
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
            // 判断条件是否匹配，可以参考OnClassCondition(对于ConditionalOnClass、ConditionalOnMissingClass的处理，一般)
            // 对于condition
            // SpringBootCondition#matches
            //      -> OnClassCondition#getMatchOutcome
            if (!condition.matches(this.context, metadata)) {
                return true;
            }
        }
    }

    return false;
}
```




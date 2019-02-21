
# Bean 实例化

我们知道Spring中对Bean的实例化都是通过 AbstractBeanFactory#getBean来实现的，下面我们详细看下这个方法的实现，以及在bean 实例化过程中的扩展点。

## AbstractBeanFactory#doGetBean

```
protected <T> T doGetBean(
        final String name, final Class<T> requiredType, final Object[] args, boolean typeCheckOnly)
        throws BeansException {
    
    // 转换bean 的名称，这里会将 工厂bean的前缀去掉，如果是通过别名获取bean，则会将别名转换为bean 实际的 beanName
    final String beanName = transformedBeanName(name);
    Object bean;

    // 获取当前的bean对象
    Object sharedInstance = getSingleton(beanName);
    if (sharedInstance != null && args == null) {
        if (logger.isDebugEnabled()) {
            if (isSingletonCurrentlyInCreation(beanName)) {
                logger.debug("Returning eagerly cached instance of singleton bean '" + beanName +
                        "' that is not fully initialized yet - a consequence of a circular reference");
            }
            else {
                logger.debug("Returning cached instance of singleton bean '" + beanName + "'");
            }
        }
        bean = getObjectForBeanInstance(sharedInstance, name, beanName, null);
    }

    else {
        // Fail if we're already creating this bean instance:
        // We're assumably within a circular reference.
        // 循环依赖中不支持 原型的创建模式
        if (isPrototypeCurrentlyInCreation(beanName)) {
            throw new BeanCurrentlyInCreationException(beanName);
        }

        // 检查父容器中是否存在已经创建的bean
        BeanFactory parentBeanFactory = getParentBeanFactory();
        if (parentBeanFactory != null && !containsBeanDefinition(beanName)) {
            // Not found -> check parent.
            String nameToLookup = originalBeanName(name);
            if (args != null) {
                // Delegation to parent with explicit args.
                return (T) parentBeanFactory.getBean(nameToLookup, args);
            }
            else {
                // No args -> delegate to standard getBean method.
                return parentBeanFactory.getBean(nameToLookup, requiredType);
            }
        }
        
        // 是否只是类型检查
        if (!typeCheckOnly) {
            // 1、将当前的bean 添加到 alreadyCreated 集合中
            // 2、从 mergedBeanDefinitions 移出
            markBeanAsCreated(beanName);
        }

        try {
            final RootBeanDefinition mbd = getMergedLocalBeanDefinition(beanName);
            // 检查当前的bean 是否是抽象类型
            checkMergedBeanDefinition(mbd, beanName, args);

            // Guarantee initialization of beans that the current bean depends on.
            // 首先初始化当前bean的依赖
            String[] dependsOn = mbd.getDependsOn();
            if (dependsOn != null) {
                for (String dep : dependsOn) {
                    if (isDependent(beanName, dep)) {
                        throw new BeanCreationException(mbd.getResourceDescription(), beanName,
                                "Circular depends-on relationship between '" + beanName + "' and '" + dep + "'");
                    }
                    registerDependentBean(dep, beanName);
                    getBean(dep);
                }
            }

            // 单例情况下创建bean的实例
            if (mbd.isSingleton()) {
                
                // 注册bean 对象工厂回调
                sharedInstance = getSingleton(beanName, new ObjectFactory<Object>() {
                    @Override
                    public Object getObject() throws BeansException {
                        try {
                            // 创建对象
                            return createBean(beanName, mbd, args);
                        }
                        catch (BeansException ex) {
                            // Explicitly remove instance from singleton cache: It might have been put there
                            // eagerly by the creation process, to allow for circular reference resolution.
                            // Also remove any beans that received a temporary reference to the bean.
                            destroySingleton(beanName);
                            throw ex;
                        }
                    }
                });
                bean = getObjectForBeanInstance(sharedInstance, name, beanName, mbd);
            }
            
            // 创建bean的原型实例
            else if (mbd.isPrototype()) {
                // It's a prototype -> create a new instance.
                Object prototypeInstance = null;
                try {
                    // 先判断原型是不是创建中
                    beforePrototypeCreation(beanName);
                    // 创建bean的实例
                    prototypeInstance = createBean(beanName, mbd, args);
                }
                finally {
                    afterPrototypeCreation(beanName);
                }
                bean = getObjectForBeanInstance(prototypeInstance, name, beanName, mbd);
            }

            // 其他情形下，例如说请求、Session 级别下实例的创建
            else {
                String scopeName = mbd.getScope();
                final Scope scope = this.scopes.get(scopeName);
                if (scope == null) {
                    throw new IllegalStateException("No Scope registered for scope name '" + scopeName + "'");
                }
                try {
                    Object scopedInstance = scope.get(beanName, new ObjectFactory<Object>() {
                        @Override
                        public Object getObject() throws BeansException {
                            beforePrototypeCreation(beanName);
                            try {
                                return createBean(beanName, mbd, args);
                            }
                            finally {
                                afterPrototypeCreation(beanName);
                            }
                        }
                    });
                    bean = getObjectForBeanInstance(scopedInstance, name, beanName, mbd);
                }
                catch (IllegalStateException ex) {
                    throw new BeanCreationException(beanName,
                            "Scope '" + scopeName + "' is not active for the current thread; consider " +
                            "defining a scoped proxy for this bean if you intend to refer to it from a singleton",
                            ex);
                }
            }
        }
        catch (BeansException ex) {
            
            // 如果bean创建失败，则从 alreadyCreated 列表中移出已经添加的引用
            cleanupAfterBeanCreationFailure(beanName);
            throw ex;
        }
    }

    // 根据需求类型，将bean转换为对应的类型
    if (requiredType != null && bean != null && !requiredType.isAssignableFrom(bean.getClass())) {
        try {
            return getTypeConverter().convertIfNecessary(bean, requiredType);
        }
        catch (TypeMismatchException ex) {
            if (logger.isDebugEnabled()) {
                logger.debug("Failed to convert bean '" + name + "' to required type '" +
                        ClassUtils.getQualifiedName(requiredType) + "'", ex);
            }
            throw new BeanNotOfRequiredTypeException(name, requiredType, bean.getClass());
        }
    }
    return (T) bean;
}
```

## DefaultSingletonBeanRegistry#getSingleton

这个方法是从单例对象中根据名字查找对应的bean，代码如下：

```
// 根据名字返回原始的对象
// 检查对于当前创建的对象是否允许早期引用（解决循环依赖的问题）
protected Object getSingleton(String beanName, boolean allowEarlyReference) {
    
    // 如果当前对象已经实例化，则直接返回
    Object singletonObject = this.singletonObjects.get(beanName);
    
    // 检查当前对象是否已经在创建中，循环依赖时会当前对象会在 singletonsCurrentlyInCreation 中
    if (singletonObject == null && isSingletonCurrentlyInCreation(beanName)) {
        synchronized (this.singletonObjects) {
            
            // 早期单例对象的引用，这个集合中的对象还没有初始化完成
            singletonObject = this.earlySingletonObjects.get(beanName);
            
            // 允许早期引用
            if (singletonObject == null && allowEarlyReference) {
                
                ObjectFactory<?> singletonFactory = this.singletonFactories.get(beanName);
                if (singletonFactory != null) {
                    // 回调对象工厂创建bean 
                    singletonObject = singletonFactory.getObject();
                    
                    this.earlySingletonObjects.put(beanName, singletonObject);
                    this.singletonFactories.remove(beanName);
                }
            }
        }
    }
    return (singletonObject != NULL_OBJECT ? singletonObject : null);
}
```

下面我们看下bean的创建过程，AbstractAutowireCapableBeanFactory#createBean
```
protected Object createBean(String beanName, RootBeanDefinition mbd, Object[] args) throws BeanCreationException {
    if (logger.isDebugEnabled()) {
        logger.debug("Creating instance of bean '" + beanName + "'");
    }
    RootBeanDefinition mbdToUse = mbd;

    // 解析beanName对应的类，主要是确保将解析好的类已经存储到  merged bean definition
    // 这里会用beanClassLoader类加载器加载bean对应的类
    Class<?> resolvedClass = resolveBeanClass(mbd, beanName);
    if (resolvedClass != null && !mbd.hasBeanClass() && mbd.getBeanClassName() != null) {
        mbdToUse = new RootBeanDefinition(mbd);
        mbdToUse.setBeanClass(resolvedClass);
    }

    // Prepare method overrides.
    try {
        mbdToUse.prepareMethodOverrides();
    }
    catch (BeanDefinitionValidationException ex) {
        throw new BeanDefinitionStoreException(mbdToUse.getResourceDescription(),
                beanName, "Validation of method overrides failed", ex);
    }

    try {
        // 在bean实例化之前调用 post-processors
        // 调用实现了 InstantiationAwareBeanPostProcessor 接口的
        // 可以参考 CommonAnnotationBeanPostProcessor 、AutowiredAnnotationBeanPostProcessor
        Object bean = resolveBeforeInstantiation(beanName, mbdToUse);
        if (bean != null) {
            return bean;
        }
    }
    catch (Throwable ex) {
        throw new BeanCreationException(mbdToUse.getResourceDescription(), beanName,
                "BeanPostProcessor before instantiation of bean failed", ex);
    }
    
    // 这里真正创建bean的过程，分析见下文
    Object beanInstance = doCreateBean(beanName, mbdToUse, args);
    if (logger.isDebugEnabled()) {
        logger.debug("Finished creating instance of bean '" + beanName + "'");
    }
    return beanInstance;
}
```

AbstractAutowireCapableBeanFactory#doCreateBean

```
protected Object doCreateBean(final String beanName, final RootBeanDefinition mbd, final Object[] args)
        throws BeanCreationException {

    // Instantiate the bean.
    BeanWrapper instanceWrapper = null;
    if (mbd.isSingleton()) {
        instanceWrapper = this.factoryBeanInstanceCache.remove(beanName);
    }
    if (instanceWrapper == null) {
        // 创建bean实例
        instanceWrapper = createBeanInstance(beanName, mbd, args);
    }
    final Object bean = (instanceWrapper != null ? instanceWrapper.getWrappedInstance() : null);
    Class<?> beanType = (instanceWrapper != null ? instanceWrapper.getWrappedClass() : null);
    mbd.resolvedTargetType = beanType;

    // Allow post-processors to modify the merged bean definition.
    synchronized (mbd.postProcessingLock) {
        if (!mbd.postProcessed) {
            try {
                // 应用 MergedBeanDefinitionPostProcessor#postProcessMergedBeanDefinition
                // 1、它会通过InitDestroyAnnotationBeanPostProcessor#buildLifecycleMetadata 对每个bean都包装为LifecycleMetadata
                // 2、CommonAnnotationBeanPostProcessor#findResourceMetadata 找出当前bean需要注入的元素，@Resource、@EJB、@WebServiceRef等
                // 3、AutowiredAnnotationBeanPostProcessor 处理 @Autowired 和 @Value
                applyMergedBeanDefinitionPostProcessors(mbd, beanType, beanName);
            }
            catch (Throwable ex) {
                throw new BeanCreationException(mbd.getResourceDescription(), beanName,
                        "Post-processing of merged bean definition failed", ex);
            }
            mbd.postProcessed = true;
        }
    }

    // 判断当前bean 是否要提前暴露，即当前bean 是单例&&允许循环依赖&&当前bean已经在创建中
    boolean earlySingletonExposure = (mbd.isSingleton() && this.allowCircularReferences &&
            isSingletonCurrentlyInCreation(beanName));
    if (earlySingletonExposure) {
        if (logger.isDebugEnabled()) {
            logger.debug("Eagerly caching bean '" + beanName +
                    "' to allow for resolving potential circular references");
        }
        
        // 添加到单例工厂 singletonFactories中，用于构建指定的单例(用于解决循环依赖)
        // singletonFactories、registeredSingletons
        addSingletonFactory(beanName, new ObjectFactory<Object>() {
            @Override
            public Object getObject() throws BeansException {
                return getEarlyBeanReference(beanName, mbd, bean);
            }
        });
    }

    // Initialize the bean instance.
    Object exposedObject = bean;
    try {
        // bean 实例化完成后 填充bean的属性
        // 这里会调用 InstantiationAwareBeanPostProcessor#postProcessAfterInstantiation
        populateBean(beanName, mbd, instanceWrapper);
        if (exposedObject != null) {
            // 填充完属性后初始化bean
            exposedObject = initializeBean(beanName, exposedObject, mbd);
        }
    }
    catch (Throwable ex) {
        if (ex instanceof BeanCreationException && beanName.equals(((BeanCreationException) ex).getBeanName())) {
            throw (BeanCreationException) ex;
        }
        else {
            throw new BeanCreationException(
                    mbd.getResourceDescription(), beanName, "Initialization of bean failed", ex);
        }
    }
    
    // 是否早期单例暴露
    if (earlySingletonExposure) {
        Object earlySingletonReference = getSingleton(beanName, false);
        if (earlySingletonReference != null) {
            if (exposedObject == bean) {
                exposedObject = earlySingletonReference;
            }
            else if (!this.allowRawInjectionDespiteWrapping && hasDependentBean(beanName)) {
                String[] dependentBeans = getDependentBeans(beanName);
                Set<String> actualDependentBeans = new LinkedHashSet<String>(dependentBeans.length);
                for (String dependentBean : dependentBeans) {
                    if (!removeSingletonIfCreatedForTypeCheckOnly(dependentBean)) {
                        actualDependentBeans.add(dependentBean);
                    }
                }
                if (!actualDependentBeans.isEmpty()) {
                    throw new BeanCurrentlyInCreationException(beanName,
                            "Bean with name '" + beanName + "' has been injected into other beans [" +
                            StringUtils.collectionToCommaDelimitedString(actualDependentBeans) +
                            "] in its raw version as part of a circular reference, but has eventually been " +
                            "wrapped. This means that said other beans do not use the final version of the " +
                            "bean. This is often the result of over-eager type matching - consider using " +
                            "'getBeanNamesOfType' with the 'allowEagerInit' flag turned off, for example.");
                }
            }
        }
    }

    // Register bean as disposable.
    try {
        
        // 注册实现了 Disposable 接口的bean
        registerDisposableBeanIfNecessary(beanName, bean, mbd);
    }
    catch (BeanDefinitionValidationException ex) {
        throw new BeanCreationException(
                mbd.getResourceDescription(), beanName, "Invalid destruction signature", ex);
    }

    return exposedObject;
}
```
AbstractAutowireCapableBeanFactory#createBeanInstance

创建bean 实例，创建正常的bean走的流程是通过BeanFactory#getBean 方法来创建bean。
但是对于某些factoryBean来说，在SpringBoot启动时通过 AbstractAutowireCapableBeanFactory#getSingletonFactoryBeanForTypeCheck
并将实例化的FactoryBean放到 factoryBeanInstanceCache 缓存中
```
protected BeanWrapper createBeanInstance(String beanName, RootBeanDefinition mbd, Object[] args) {
    // 确保此刻bean已经bean 解析并且已加载
    Class<?> beanClass = resolveBeanClass(mbd, beanName);

    // 确保要创建的bean 类型为public
    if (beanClass != null && !Modifier.isPublic(beanClass.getModifiers()) && !mbd.isNonPublicAccessAllowed()) {
        throw new BeanCreationException(mbd.getResourceDescription(), beanName,
                "Bean class isn't public, and non-public access not allowed: " + beanClass.getName());
    }
    
    // 通过对应的工厂创建bean
    if (mbd.getFactoryMethodName() != null)  {
        return instantiateUsingFactoryMethod(beanName, mbd, args);
    }

    // Shortcut when re-creating the same bean...
    boolean resolved = false;
    boolean autowireNecessary = false;
    if (args == null) {
        synchronized (mbd.constructorArgumentLock) {
            if (mbd.resolvedConstructorOrFactoryMethod != null) {
                resolved = true;
                autowireNecessary = mbd.constructorArgumentsResolved;
            }
        }
    }
    if (resolved) {
        if (autowireNecessary) {
            return autowireConstructor(beanName, mbd, null, null);
        }
        else {
            return instantiateBean(beanName, mbd);
        }
    }

    // 决定要实例化bean的构造器
    // 参考 AutowiredAnnotationBeanPostProcessor，它会将每个bean 类型对应的构造器缓存起来
    Constructor<?>[] ctors = determineConstructorsFromBeanPostProcessors(beanClass, beanName);
    if (ctors != null ||
            mbd.getResolvedAutowireMode() == RootBeanDefinition.AUTOWIRE_CONSTRUCTOR ||
            mbd.hasConstructorArgumentValues() || !ObjectUtils.isEmpty(args))  {
        return autowireConstructor(beanName, mbd, ctors, args);
    }

    // 默认是有无参构造器
    return instantiateBean(beanName, mbd);
}
```

AbstractAutowireCapableBeanFactory#instantiateBean

```
protected BeanWrapper instantiateBean(final String beanName, final RootBeanDefinition mbd) {
    try {
        Object beanInstance;
        final BeanFactory parent = this;
        if (System.getSecurityManager() != null) {
            beanInstance = AccessController.doPrivileged(new PrivilegedAction<Object>() {
                @Override
                public Object run() {
                    return getInstantiationStrategy().instantiate(mbd, beanName, parent);
                }
            }, getAccessControlContext());
        }
        else {
            // 获取实例化策略，然后实例化bean
            // SimpleInstantiationStrategy
            // 具体实例化参考 BeanUtils.instantiateClass(constructorToUse)
            beanInstance = getInstantiationStrategy().instantiate(mbd, beanName, parent);
        }
        BeanWrapper bw = new BeanWrapperImpl(beanInstance);
        
        // 注册 PropertyEditorRegistrar ，后续用户某些属性的修改
        initBeanWrapper(bw);
        
        // 返回 包装后的 bean，这里bean 已经通过反射实例化完成，后续会进行属性的填充
        return bw;
    }
    catch (Throwable ex) {
        throw new BeanCreationException(
                mbd.getResourceDescription(), beanName, "Instantiation of bean failed", ex);
    }
}
```

AbstractAutowireCapableBeanFactory#populateBean 

bean实例化完成后，填充bean的属性

```
protected void populateBean(String beanName, RootBeanDefinition mbd, BeanWrapper bw) {
    
    // 获取属性值
    PropertyValues pvs = mbd.getPropertyValues();

    if (bw == null) {
        if (!pvs.isEmpty()) {
            throw new BeanCreationException(
                    mbd.getResourceDescription(), beanName, "Cannot apply property values to null instance");
        }
        else {
            // Skip property population phase for null instance.
            return;
        }
    }

    // Give any InstantiationAwareBeanPostProcessors the opportunity to modify the
    // state of the bean before properties are set. This can be used, for example,
    // to support styles of field injection.
    boolean continueWithPropertyPopulation = true;
    
    // 调用 InstantiationAwareBeanPostProcessors#postProcessAfterInstantiation
    if (!mbd.isSynthetic() && hasInstantiationAwareBeanPostProcessors()) {
        for (BeanPostProcessor bp : getBeanPostProcessors()) {
            if (bp instanceof InstantiationAwareBeanPostProcessor) {
                InstantiationAwareBeanPostProcessor ibp = (InstantiationAwareBeanPostProcessor) bp;
                if (!ibp.postProcessAfterInstantiation(bw.getWrappedInstance(), beanName)) {
                    continueWithPropertyPopulation = false;
                    break;
                }
            }
        }
    }

    if (!continueWithPropertyPopulation) {
        return;
    }
    
    // 注入属性类型
    if (mbd.getResolvedAutowireMode() == RootBeanDefinition.AUTOWIRE_BY_NAME ||
            mbd.getResolvedAutowireMode() == RootBeanDefinition.AUTOWIRE_BY_TYPE) {
        MutablePropertyValues newPvs = new MutablePropertyValues(pvs);

        // Add property values based on autowire by name if applicable.
        if (mbd.getResolvedAutowireMode() == RootBeanDefinition.AUTOWIRE_BY_NAME) {
            autowireByName(beanName, mbd, bw, newPvs);
        }

        // Add property values based on autowire by type if applicable.
        if (mbd.getResolvedAutowireMode() == RootBeanDefinition.AUTOWIRE_BY_TYPE) {
            autowireByType(beanName, mbd, bw, newPvs);
        }

        pvs = newPvs;
    }

    boolean hasInstAwareBpps = hasInstantiationAwareBeanPostProcessors();
    boolean needsDepCheck = (mbd.getDependencyCheck() != RootBeanDefinition.DEPENDENCY_CHECK_NONE);

    if (hasInstAwareBpps || needsDepCheck) {
        
        // 属性描述器
        PropertyDescriptor[] filteredPds = filterPropertyDescriptorsForDependencyCheck(bw, mbd.allowCaching);
        if (hasInstAwareBpps) {
            for (BeanPostProcessor bp : getBeanPostProcessors()) {
                if (bp instanceof InstantiationAwareBeanPostProcessor) {
                    // 参考CommonAnnotationBeanPostProcessor 处理@Resource属性的注入,通过 InjectionMetadata#inject 实现，
                    // 最终还是会调用AbstractBeanFactory#getBean 获取对应的bean，然后通过反射实现注入
                    InstantiationAwareBeanPostProcessor ibp = (InstantiationAwareBeanPostProcessor) bp;
                    pvs = ibp.postProcessPropertyValues(pvs, filteredPds, bw.getWrappedInstance(), beanName);
                    if (pvs == null) {
                        return;
                    }
                }
            }
        }
        if (needsDepCheck) {
            checkDependencies(beanName, mbd, filteredPds, pvs);
        }
    }
    
    // 解析运行时引用其他的bean
    applyPropertyValues(beanName, mbd, bw, pvs);
}

```


AbstractAutowireCapableBeanFactory#initializeBean

```
// 初始化Bean
protected Object initializeBean(final String beanName, final Object bean, RootBeanDefinition mbd) {
    if (System.getSecurityManager() != null) {
        AccessController.doPrivileged(new PrivilegedAction<Object>() {
            @Override
            public Object run() {
                invokeAwareMethods(beanName, bean);
                return null;
            }
        }, getAccessControlContext());
    }
    else {
        // 首先调用 实现了Aware 方法，扩展点之一
        invokeAwareMethods(beanName, bean);
    }

    Object wrappedBean = bean;
    if (mbd == null || !mbd.isSynthetic()) {
        // 调用BeanPostProcessor#postProcessBeforeInitialization 方法
        // CommonAnnotationBeanPostProcessor->InitDestroyAnnotationBeanPostProcessor#postProcessBeforeInitialization 处理 @PostConstruct 和 @PreDestroy 注解定义的方法
        wrappedBean = applyBeanPostProcessorsBeforeInitialization(wrappedBean, beanName);
    }

    try {
        // 如果bean 实现了 InitializingBean，那么调用 InitializingBean#afterPropertiesSet
        invokeInitMethods(beanName, wrappedBean, mbd);
    }
    catch (Throwable ex) {
        throw new BeanCreationException(
                (mbd != null ? mbd.getResourceDescription() : null),
                beanName, "Invocation of init method failed", ex);
    }

    if (mbd == null || !mbd.isSynthetic()) {
        // 调用 BeanPostProcessor#applyBeanPostProcessorsAfterInitialization
        // 是否会生成代理类 AbstractAdvisingBeanPostProcessor#postProcessAfterInitialization 
        // 
        wrappedBean = applyBeanPostProcessorsAfterInitialization(wrappedBean, beanName);
    }
    return wrappedBean;
}
```

以上就是bean的创建过程，通过上面分析，我们看下在bean的创建、初始化过程中的几个扩展点。

## 创建bean扩展点

- 1、BeanPostProcessor#postProcessBeforeInstantiation


**2、实例化完成后调用合并bean definition**

- 1、BeanPostProcessor#postProcessMergedBeanDefinition

**3、实例化后**

1、BeanPostProcessor#postProcessAfterInitialization

**4、处理属性值**

这个过程会处理@Resource、@AutoWired、@Valued注解，并注入属性值
1、BeanPostProcessor#postProcessPropertyValues

通过 下面方法实现对@Valued值的解析
applyPropertyValues
**1、实例化前**

**5、处理Aware接口

**6、处理 BeanPostProcessor#postProcessBeforeInitialization

这里会通过 InstantiationAwareBeanPostProcessor 处理 PostConstruct、PreDestroy

7、调用 InitializingBean# afterPropertiesSet

8、调用 BeanPostProcessor#postProcessAfterInitialization

9、注册 DisposableBean 接口
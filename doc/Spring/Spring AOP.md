# Spring AOP 

Spring AOP 也是基于Java的动态代理实现的，目前常用的两种动态代理模式：JDK动态代理 和 CGLIB动态代理。

## JDK 动态代理
Jdk 动态代理是基于接口的，也就是说要使用JDK的动态代理，被代理对象必须有接口，否则是无法实现的。下面我们通过一段简单的代码示例来说明下JDK动态代理的原理。

```
public interface UserService {

	void getUserName();
}

// 被代理类
public class UserServiceImpl implements UserService {

	public void getUserName() {
		System.out.println("hello world!!");
	}
}

// 增强类
public class LogProxy implements InvocationHandler {

	private Object target;

	public Object getProxy(Object o){
		target = o;
		return Proxy.newProxyInstance(o.getClass().getClassLoader(),o.getClass().getInterfaces(),this);
	}


	public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {

		System.out.println("before:" + target.getClass().getSimpleName()+"#" + method.getName());
		Object o = method.invoke(target, args);
		System.out.println("after:" + target.getClass().getSimpleName()+"#" + method.getName());
		return o;
	}
}

```
下面代码就是通过jdk代理生成的代理类。对于jdk生成的动态代理类可以通过 **-Dsun.misc.ProxyGenerator.saveGeneratedFiles=true** 来保存生成的类信息。
可以看出jdk生成的代理类是继承Proxy类并实现被代理类的接口，然后通过自己写的代理类(InvocationHandler) 来实现对于原有方法的增强。
```
package com.sun.proxy;

public final class $Proxy0 extends java.lang.reflect.Proxy implements com.maxam.proxy.UserService {
    private static java.lang.reflect.Method m1;
    private static java.lang.reflect.Method m2;
    private static java.lang.reflect.Method m3;
    private static java.lang.reflect.Method m0;

    public $Proxy0(java.lang.reflect.InvocationHandler invocationHandler) { /* compiled code */ }

    public final boolean equals(java.lang.Object o) { /* compiled code */ }

    public final java.lang.String toString() { /* compiled code */ }

    public final void getUserName() { /* compiled code */ 
        // 伪代码
        invocationHandler.invoke(this,getUserName,null)
    }

    public final int hashCode() { /* compiled code */ }
}
```
## CGLIB 动态代理

CGLIB 动态代理是基于类的，下面我们通过一小段代码来看CGLIB的动态代理。

```

public interface UserService {

	void getUserName();
}

// 被代理类
public class UserServiceImpl implements UserService {

	public void getUserName() {
		System.out.println("hello world!!");
	}
}

// 增强类
public class CglibProxy implements MethodInterceptor{

    private Enhancer enhancer = new Enhancer();

    public Object getProxy(Class clazz){
        enhancer.setSuperclass(clazz);
        enhancer.setCallback(this);
        return enhancer.create();
    }

    public Object intercept(Object o, Method method, Object[] objects, MethodProxy methodProxy) throws Throwable {
        PerformanceMonior.begin(o.getClass().getName(),method.getName());
        Object result = methodProxy.invokeSuper(o,objects);
        PerformanceMonior.end(o.getClass().getName(),method.getName(),result);
        return result;
    }
}

```

下面的代码为cglib生成的代理类，通过可以通过设置如下属性，以便保存cglib生成的代理类：
- System.setProperty(DebuggingClassWriter.DEBUG_LOCATION_PROPERTY, path);

通过代理类我们可以才看出，CGLIB生成代理类的方式是通过继承被代理类，重写被代理类的方法实现来实现增强的逻辑。
```
package com.maxam.proxy.Impl;

public class UserServiceImpl$$EnhancerByCGLIB$$d855f607 extends com.maxam.proxy.Impl.UserServiceImpl implements net.sf.cglib.proxy.Factory {
    private boolean CGLIB$BOUND;
    private static final java.lang.ThreadLocal CGLIB$THREAD_CALLBACKS;
    private static final net.sf.cglib.proxy.Callback[] CGLIB$STATIC_CALLBACKS;
    private net.sf.cglib.proxy.MethodInterceptor CGLIB$CALLBACK_0;
    private static final java.lang.reflect.Method CGLIB$add$0$Method;
    private static final net.sf.cglib.proxy.MethodProxy CGLIB$add$0$Proxy;
    private static final java.lang.Object[] CGLIB$emptyArgs;
    private static final java.lang.reflect.Method CGLIB$finalize$1$Method;
    private static final net.sf.cglib.proxy.MethodProxy CGLIB$finalize$1$Proxy;
    private static final java.lang.reflect.Method CGLIB$equals$2$Method;
    private static final net.sf.cglib.proxy.MethodProxy CGLIB$equals$2$Proxy;
    private static final java.lang.reflect.Method CGLIB$toString$3$Method;
    private static final net.sf.cglib.proxy.MethodProxy CGLIB$toString$3$Proxy;
    private static final java.lang.reflect.Method CGLIB$hashCode$4$Method;
    private static final net.sf.cglib.proxy.MethodProxy CGLIB$hashCode$4$Proxy;
    private static final java.lang.reflect.Method CGLIB$clone$5$Method;
    private static final net.sf.cglib.proxy.MethodProxy CGLIB$clone$5$Proxy;

    static void CGLIB$STATICHOOK1() { /* compiled code */ }

    final int CGLIB$add$0(int i, int i1) { /* compiled code */ }

    public final int add(int i, int i1) { /* compiled code */ }

    final void CGLIB$finalize$1() throws java.lang.Throwable { /* compiled code */ }

    protected final void finalize() throws java.lang.Throwable { /* compiled code */ }

    final boolean CGLIB$equals$2(java.lang.Object o) { /* compiled code */ }

    public final boolean equals(java.lang.Object o) { /* compiled code */ }

    final java.lang.String CGLIB$toString$3() { /* compiled code */ }

    public final java.lang.String toString() { /* compiled code */ }

    final int CGLIB$hashCode$4() { /* compiled code */ }

    public final int hashCode() { /* compiled code */ }

    final java.lang.Object CGLIB$clone$5() throws java.lang.CloneNotSupportedException { /* compiled code */ }

    protected final java.lang.Object clone() throws java.lang.CloneNotSupportedException { /* compiled code */ }

    public static net.sf.cglib.proxy.MethodProxy CGLIB$findMethodProxy(net.sf.cglib.core.Signature signature) { /* compiled code */ }

    public UserServiceImpl$$EnhancerByCGLIB$$d855f607() { /* compiled code */ }

    public static void CGLIB$SET_THREAD_CALLBACKS(net.sf.cglib.proxy.Callback[] callbacks) { /* compiled code */ }

    public static void CGLIB$SET_STATIC_CALLBACKS(net.sf.cglib.proxy.Callback[] callbacks) { /* compiled code */ }

    private static final void CGLIB$BIND_CALLBACKS(java.lang.Object o) { /* compiled code */ }

    public java.lang.Object newInstance(net.sf.cglib.proxy.Callback[] callbacks) { /* compiled code */ }

    public java.lang.Object newInstance(net.sf.cglib.proxy.Callback callback) { /* compiled code */ }

    public java.lang.Object newInstance(java.lang.Class[] classes, java.lang.Object[] objects, net.sf.cglib.proxy.Callback[] callbacks) { /* compiled code */ }

    public net.sf.cglib.proxy.Callback getCallback(int i) { /* compiled code */ }

    public void setCallback(int i, net.sf.cglib.proxy.Callback callback) { /* compiled code */ }

    public net.sf.cglib.proxy.Callback[] getCallbacks() { /* compiled code */ }

    public void setCallbacks(net.sf.cglib.proxy.Callback[] callbacks) { /* compiled code */ }
}
```

## Spring 动态代理


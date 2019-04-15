package com.myspring.mvcframework.v2.servlet;

import com.myspring.mvcframework.annotation.*;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.*;

/**
 * 重写init()、doPost()、doGet()方法
 * @author: diaoche
 * @review:
 * @date: 2019/4/15 11:23
 */
public class GPDispatcherServlet extends HttpServlet {

    //保存application.properties配置文件中的内容
    private Properties contextConfig = new Properties();

    //保存所有扫描的雷鸣
    private List<String> classNames = new ArrayList<String>();

    //传说中的SpringIoc,我们就来解开他的原理
    private Map<String,Object> ioc = new HashMap<String,Object>();

    //保存url和Method的对应关系
    private Map<String,Method> handlerMapping = new HashMap<String, Method>();



    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        this.doPost(req,resp);
    }


    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        //6、调用，运行阶段
        try {
            doDispatch(req,resp);
        } catch (Exception e) {
            e.printStackTrace();
            resp.getWriter().write("500 Exection,Detail : " + Arrays.toString(e.getStackTrace()));
        }
    }

    //运行阶段
    private void doDispatch(HttpServletRequest req, HttpServletResponse resp) throws Exception{
        //绝对路径
        String url = req.getRequestURI();
        //转换为相对路径
        String contextPath = req.getContextPath();
        url = url.replaceAll(contextPath,"").replaceAll("/+","/");

        if(!this.handlerMapping.containsKey(url)){
            resp.getWriter().write("404 Not Found!!");
            return;
        }

        Method method = this.handlerMapping.get(url);

        //从request中拿到url的参数
        Map<String,String[]> params = req.getParameterMap();
//
//        //获取方法的形参列表
//        Class<?>[] parameterTypes = method.getParameterTypes();
//
//        Object[] paramValues = new Object[parameterTypes.length];

//        for (int i = 0; i < parameterTypes.length; i++) {
//            Class<?> parameterType = parameterTypes[i];
//            //不能用instanceof，parameterType它不是实参，而是形参
//            if(parameterType == HttpServletRequest.class){
//                paramValues[i] = req;
//                continue;
//            }else if(parameterType == HttpServletResponse.class){
//                paramValues[i] = resp;
//                continue;
//            }else if(parameterType == String.class){
//                GPRequestParam requestParam = (GPRequestParam)parameterType.getAnnotation(GPRequestParam.class);
//                if(params.containsKey(requestParam.value())){
//                    for (Map.Entry<String, String[]> param : params.entrySet()) {
//                        String value = Arrays.toString(param.getValue())
//                                .replaceAll("\\[|\\]","")
//                                .replaceAll("\\s",",");
//                        paramValues[i] = value;
//                    }
//                }
//            }
//        }

        //投机取巧的方式
        //通过反射拿到method所在class，拿到class之后还是拿到class的名称
        //再调用toLowerFirstCase获得beanName
        String beanName  = toLowerFirstCase(method.getDeclaringClass().getSimpleName());
        method.invoke(ioc.get(beanName),new Object[]{req,resp,params.get("name")[0]});

    }


    //初始化阶段
    @Override
    public void init(ServletConfig config) throws ServletException {
        //1、加载配置文件
        doLoadConfig(config.getInitParameter("contextConfigLocation"));
        
        //2、扫描相关的类
        doScanner(contextConfig.getProperty("scanPackage"));
        
        //3、初始化扫描的类，并且将他们放入IOC容器中
        doInstance();

        //4、完成依赖注入
        doAutowired();

        //初始化initHandlerMapping
        initHandlerMapping();

        System.out.println("GP Spring framework is init.");
    }

    //初始化url与Method的一对一关系
    private void initHandlerMapping() {
        if(ioc.isEmpty()){return;}

        for (Map.Entry<String, Object> entry : ioc.entrySet()) {
            Class<?> clazz = entry.getValue().getClass();
            if(!clazz.isAnnotationPresent(GPController.class)){
                return;
            }

            //保存写在类上的@GPRequestMapping("/demo")
            String baseUrl = "";
            if(clazz.isAnnotationPresent(GPRequestMapping.class)){
                GPRequestMapping requestMapping = clazz.getAnnotation(GPRequestMapping.class);
                baseUrl = requestMapping.value();
            }

            //默认获取所有的public方法
            for (Method method : clazz.getMethods()) {
                if(!method.isAnnotationPresent(GPRequestMapping.class)){continue;}
                GPRequestMapping requestMapping = method.getAnnotation(GPRequestMapping.class);

                String value = requestMapping.value();
                //优化
                // //demo///query
                String url = ("/" +baseUrl+ "/" + value).replaceAll("/+","/");

                handlerMapping.put(url,method);
                System.out.println("Mapped :" + url + "  ====  " + method);
            }
        }
    }

    //自动依赖注入
    private void doAutowired() {
        if(ioc.isEmpty()){return;}

        for (Map.Entry<String, Object> entry : ioc.entrySet()) {
            //Declared 所有的，特定的 字段，包括private/protected/default
            //正常来说，普通的OOP编程只能拿到public的属性
            Field[] fields = entry.getValue().getClass().getDeclaredFields();

            for (Field field : fields) {
                if(!field.isAnnotationPresent(GPAutowired.class)){
                    continue;
                }
                GPAutowired autowired = field.getAnnotation(GPAutowired.class);

                //如果用户没有自定义beanName，默认就根据类型注入
                //这个地方省去了对类名首字母小写的情况的判断
                String beanName = autowired.value().trim();
                if("".equals(beanName)){
                    //获取接口类型，作为key待会那这个key在ioc容器中取值
                    beanName = field.getType().getName();
                }

                //如果是public以外的修饰符，只要加了@Autowired注解，都要强制赋值
                //反射中叫做暴力访问， 强吻
                field.setAccessible(true);
                try {
                    field.set(entry.getValue(),ioc.get(beanName));
                }catch (Exception e){
                    e.printStackTrace();
                }
            }
        }
    }

    //初始化 为DI做准备
    private void doInstance() {
        if(classNames.isEmpty()){return;}

        try{
            for (String className : classNames) {
                Class<?> clazz = Class.forName(className);
                //什么样的类才需要初始化呢？
                //加了注解的类，才初始化，怎么判断？
                //主要是体会设计思想，只列举@Controller和@Service,

                if(clazz.isAnnotationPresent(GPController.class)){
                    Object instance = clazz.newInstance();
                    //spring默认类名首字母小写
                    String beanName = toLowerFirstCase(clazz.getSimpleName());
                    ioc.put(beanName,instance);
                }else if(clazz.isAnnotationPresent(GPService.class)){
                    //1、自定义的beanName
                    GPService service = clazz.getAnnotation(GPService.class);
                    String beanName = service.value();
                    //2、默认类名首字母小写
                    if("".equals(beanName)){
                        beanName = toLowerFirstCase(clazz.getSimpleName());
                    }
                    Object instance = clazz.newInstance();
                    ioc.put(beanName,instance);

                    //根据类型赋值，投机取巧的方式
                    for (Class<?> anInterface : clazz.getInterfaces()) {
                        if(ioc.containsKey(anInterface.getName())){
                            throw new Exception("The “" + anInterface.getName() + "” is exists!!");
                        }
                        //把接口的类型值直接当成key
                        ioc.put(anInterface.getName(),instance);
                    }
                }else {
                    continue;
                }
            }
        }catch (Exception e){
            e.printStackTrace();
        }
    }

    //如果类名本身是小写字母，确实会出问题
    //但是我要说明的是：这个方法是我自己用，private的
    //传值也是自己传，类也都遵循了驼峰命名法
    //默认传入的值，存在首字母小写的情况，也不可能出现非字母的情况
    private String toLowerFirstCase(String simpleName) {
        char[] chars = simpleName.toCharArray();
        //之所以加，是因为大小写字母的ASCII码相差32，
        // 而且大写字母的ASCII码要小于小写字母的ASCII码
        //在Java中，对char做算学运算，实际上就是对ASCII码做算学运算
        chars[0] += 32;
        return String.valueOf(chars);

    }

    //扫描相关的类
    private void doScanner(String scanPackage) {
        //scanPackage=com.myspring.demo ,存储的是包路径
        //转换为文件路径，实际上就是把 . 替换为 / 就行了
        //classpath
        URL url = this.getClass().getClassLoader().getResource("/" + scanPackage.replaceAll("\\.", "/"));

        File classPath = new File(url.getFile());
        for (File file : classPath.listFiles()) {
            if(file.isDirectory()){
                doScanner(scanPackage + "." + file.getName());
            }else{
                if(!file.getName().endsWith(".class")){continue;}
                String className  = (scanPackage + "." + file.getName().replace(".class",""));
                classNames.add(className);
            }
        }
    }


    //加载配置文件
    private void doLoadConfig(String contextConfigLocation) {
        //直接从类路径下找到Spring主配置文件所在的路径
        //并且将其读取出来放在Properties对象中
        //相当于 scanPackage=com.myspring.demo 从文件中保存在内存中
        InputStream fis = this.getClass().getClassLoader().getResourceAsStream(contextConfigLocation);

        try{
            contextConfig.load(fis);
        }catch (Exception e){
            e.printStackTrace();
        }finally {
            if(fis!=null){
                try{
                    fis.close();
                }catch (Exception e){
                    e.printStackTrace();
                }
            }
        }
    }
}

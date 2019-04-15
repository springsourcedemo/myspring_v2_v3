package com.myspring.mvcframework.v3.servlet;

import com.myspring.mvcframework.annotation.*;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
//    private Map<String,Method> handlerMapping = new HashMap<String, Method>();

    //思考：为什么不用Map
    //你用Map的话，key，只能是url
    //Handler 本身的功能就是把url和method对应关系，已经具备了Map的功能
    //根据设计原则：冗余的感觉了，单一职责，最少知道原则，帮助我们更好的理解
    private List<HandlerMapping>  handlerMappingList = new ArrayList<HandlerMapping>();



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
        HandlerMapping handler = getHandler(req);
        if(null == handler){
            resp.getWriter().write("404 Not Found!!");
            return;
        }
        //获取方法的形参列表
        Class<?>[] parameterTypes = handler.getParamTypes();

        Object[] paramValues = new Object[parameterTypes.length];
        //从request中拿到url的参数
        Map<String,String[]> params = req.getParameterMap();

        for (Map.Entry<String, String[]> param : params.entrySet()) {
            String value = Arrays.toString(param.getValue()).replaceAll("\\[|\\]","")
                    .replaceAll("\\s",",");
            if(!handler.paramIndexMapping.containsKey(param.getKey())){
                continue;
            }

            Integer index = handler.paramIndexMapping.get(param.getKey());

            paramValues[index] = convert(parameterTypes[index],value);
        }

        if(handler.paramIndexMapping.containsKey(HttpServletRequest.class.getName())){
            Integer index = handler.paramIndexMapping.get(HttpServletRequest.class.getName());
            paramValues[index] = req;
        }

        if(handler.paramIndexMapping.containsKey(HttpServletResponse.class.getName())){
            Integer index = handler.paramIndexMapping.get(HttpServletResponse.class.getName());
            paramValues[index] = resp;
        }

        Object returnValue = handler.method.invoke(handler.controller, paramValues);

        if(returnValue == null || returnValue instanceof Void){
            return;
        }

        resp.getWriter().write(returnValue.toString());
    }

    //url传过来的参数都是String类型的，HTTP是基于字符串协议
    //只需要把String转换为任意类型就好
    private Object convert(Class<?> type,String value){
        //如果是int
        if(Integer.class == type){
            return Integer.valueOf(value);
        }
        else if(Double.class == type){
            return Double.valueOf(value);
        }
        //如果还有double或者其他类型，继续加if
        //这时候，我们应该想到策略模式了
        //在这里暂时不实现，希望小伙伴自己来实现
        return value;
    }
    private HandlerMapping getHandler(HttpServletRequest req) {
        if(handlerMappingList.isEmpty()){return null;}
        //绝对路径
        String url = req.getRequestURI();
        //转换为相对路径
        String contextPath = req.getContextPath();
        url = url.replaceAll(contextPath,"").replaceAll("/+","/");

        for (HandlerMapping handlerMapping : this.handlerMappingList) {
            Matcher matcher = handlerMapping.getPattern().matcher(url);
            if(!matcher.matches()){
                continue;
            }
            return handlerMapping;
        }
        return null;
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
                String regex = ("/" +baseUrl+ "/" + value).replaceAll("/+","/");

                Pattern pattern = Pattern.compile(regex);
                this.handlerMappingList.add(new HandlerMapping(pattern,entry.getValue(),method));

                System.out.println("Mapped :" + pattern + "  ====  " + method);
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


    //保存一个url和一个Mothod的关系
    public class HandlerMapping{
        //必须把url放到HandlerMapping才好理解吧
        private Pattern pattern;//正则-----url
        private Method method;
        private Object controller;
        private Class<?>[] paramTypes;

        //形参列表
        //参数的名字作为key,参数的顺序，位置作为值
        private Map<String,Integer> paramIndexMapping;

        public HandlerMapping(Pattern pattern, Object controller, Method method) {
            this.pattern = pattern;
            this.method = method;
            this.controller = controller;

            paramTypes = method.getParameterTypes();

            paramIndexMapping = new HashMap<String, Integer>();

            putParamIndexMapping(method);

        }

        private void putParamIndexMapping(Method method) {
            //提取方法中加了注解的参数
            //把方法上的注解拿到，得到的是一个二维数组
            //因为一个参数可以有多个注解，而一个方法又有多个参数
            Annotation[] [] pa = method.getParameterAnnotations();
            for (int i = 0; i < pa.length ; i ++) {
                for(Annotation a : pa[i]){
                    if(a instanceof GPRequestParam){
                        String paramName = ((GPRequestParam) a).value();
                        if(!"".equals(paramName.trim())){
                            paramIndexMapping.put(paramName, i);
                        }
                    }
                }
            }

            //提取方法中的request和response参数
            Class<?> [] paramsTypes = method.getParameterTypes();
            for (int i = 0; i < paramsTypes.length ; i ++) {
                Class<?> type = paramsTypes[i];
                if(type == HttpServletRequest.class ||
                        type == HttpServletResponse.class){
                    paramIndexMapping.put(type.getName(),i);
                }
            }

        }

        public Pattern getPattern() {
            return pattern;
        }

        public Method getMethod() {
            return method;
        }

        public Object getController() {
            return controller;
        }

        public Class<?>[] getParamTypes() {
            return paramTypes;
        }
    }
}

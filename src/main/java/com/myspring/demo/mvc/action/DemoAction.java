package com.myspring.demo.mvc.action;

import com.myspring.demo.service.IDemoService;
import com.myspring.mvcframework.annotation.GPAutowired;
import com.myspring.mvcframework.annotation.GPController;
import com.myspring.mvcframework.annotation.GPRequestMapping;
import com.myspring.mvcframework.annotation.GPRequestParam;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * @author: diaoche
 * @review:
 * @date: 2019/4/15 10:08
 */

@GPController
@GPRequestMapping("/demo")
public class DemoAction {
    @GPAutowired
    private IDemoService demoService;

    @GPRequestMapping("/query.*")
    public void query(HttpServletRequest req, HttpServletResponse resp,
                      @GPRequestParam("name") String name){
//		String result = demoService.get(name);
        String result = "My name is " + name;
        try {
            resp.getWriter().write(result);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @GPRequestMapping("/add")
    public void add(HttpServletRequest req, HttpServletResponse resp,
                    @GPRequestParam("a") Integer a, @GPRequestParam("b") Integer b){
        try {
            resp.getWriter().write(a + "+" + b + "=" + (a + b));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @GPRequestMapping("/sub")
    public void sub(HttpServletRequest req, HttpServletResponse resp,
                    @GPRequestParam("a") Double a, @GPRequestParam("b") Double b){
        try {
            resp.getWriter().write(a + "-" + b + "=" + (a - b));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @GPRequestMapping("/remove")
    public String  remove(@GPRequestParam("id") Integer id){
        return "" + id;
    }
}

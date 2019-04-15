package com.myspring.demo.service.impl;

import com.myspring.demo.service.IDemoService;
import com.myspring.mvcframework.annotation.GPService;

/**
 * @author: diaoche
 * @review:
 * @date: 2019/4/15 10:10
 */
@GPService
public class DemoService implements IDemoService {

    @Override
    public String get(String name) {
        return "My Name is " + name;
    }
}

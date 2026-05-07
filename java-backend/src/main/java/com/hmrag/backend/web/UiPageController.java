package com.hmrag.backend.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class UiPageController {

    @GetMapping({"/ui", "/ui/", "/ui/index", "/ui/query"})
    public String queryEntry() {
        return "forward:/ui/index.html";
    }

    @GetMapping({"/ui/ops", "/ui/ops/"})
    public String opsEntry() {
        return "forward:/ui/ops.html";
    }
}


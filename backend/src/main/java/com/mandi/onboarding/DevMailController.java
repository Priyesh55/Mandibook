package com.mandi.onboarding;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** DEVELOPMENT AND TEST ONLY: shows the invitation links the {@link DevMailSink} captured. Not registered in any other configuration. */
@RestController
@RequestMapping("/api/v1/dev")
@ConditionalOnProperty(name = "mandi.mail.dev-sink", havingValue = "true")
public class DevMailController {

    private final DevMailSink sink;

    public DevMailController(DevMailSink sink) {
        this.sink = sink;
    }

    @GetMapping("/mail")
    public List<DevMailSink.Message> mail() {
        return sink.recent();
    }
}

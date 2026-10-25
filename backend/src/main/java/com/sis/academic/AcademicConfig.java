package com.sis.academic;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on @Scheduled (used to refresh the autocomplete index). */
@Configuration
@EnableScheduling
public class AcademicConfig {
}

package br.com.sergio.sapiensia.alert;

import org.springframework.context.ApplicationContext;

/** Lets tests of other packages run one alert dispatch (the dispatch itself is package-private). */
public final class AdminAlertTestAccess {

    private AdminAlertTestAccess() {}

    /** Runs one dispatch and returns how many alert e-mails it sent. */
    public static int dispatch(ApplicationContext context) {
        return context.getBean(AdminAlertDispatch.class).run().sent();
    }
}

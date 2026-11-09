package br.com.sergio.sapiensia.admin;

import java.util.List;

/** The answer that shows the recovery codes, once. {@code toString()} hides them: MVC logs bodies at DEBUG. */
record RecoveryCodeList(List<String> codes) {

    RecoveryCodeList {
        codes = List.copyOf(codes);
    }

    @Override
    public String toString() {
        return "RecoveryCodeList[redacted]";
    }
}

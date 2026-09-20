package de.regelsuche.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/** A registered fault fixture: acknowledges paid startup, then never replies to a query. */
public final class WorkReplacementHangingProcessFixture {
    public static void main(String[] args) throws Exception {
        var input = new BufferedReader(new InputStreamReader(System.in));
        input.readLine();
        var receipt = LifecycleWorkAccount.Receipt.measured("startup", LifecycleWorkAccount.Phase.COMPILATION,
            7, "hanging-fixture/v1", "known startup work");
        System.out.println(new ObjectMapper().writeValueAsString(List.of(receipt)));
        input.readLine();
        new CountDownLatch(1).await();
    }
}

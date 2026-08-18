package com.praksa;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test") // supplies BUG-15 test secrets (jwt.secret / DB password) so the context can load
class PraksaApplicationTests {

	@Test
	void contextLoads() {
	}

}

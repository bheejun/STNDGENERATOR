package kr.wise.csr;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class CommonStandardRuleBuilderApplicationTests {
	@Autowired
	Environment environment;

	@Test
	void contextLoads() {
	}

	@Test
	void usesTheWdqLocalServerPortByDefault() {
		assertThat(environment.getProperty("server.port", Integer.class)).isEqualTo(38081);
	}

}

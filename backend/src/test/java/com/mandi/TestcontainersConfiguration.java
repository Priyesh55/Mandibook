package com.mandi;

import java.util.UUID;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One PostgreSQL container shared by every Spring context in the test run (synthetic data only).
 *
 * <p>The tests run the way production is meant to run: Flyway migrates as the container's owner (the
 * migrator), while the application itself connects as the restricted runtime role {@code mandi_app}, which is
 * created here (the real setup script is scripts/db/setup-runtime-role.sh) and receives its table privileges from
 * {@code R__runtime_grants.sql}. The application therefore cannot do anything in these tests that the real runtime
 * role cannot do. Fixture code that needs more (cleaning tables, creating firms) uses the injected
 * {@code JdbcTemplate}, which is deliberately the OWNER connection, never the application's own connection.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	public static final String RUNTIME_ROLE = "mandi_app";
	/** Random per test run; never reused anywhere. */
	public static final String RUNTIME_PASSWORD = UUID.randomUUID().toString();
	/** The platform role (scripts/db/setup-platform-role.sh). Cluster-wide, so migration tests that create their own databases get its grants. */
	public static final String PLATFORM_ROLE = "mandi_platform";
	public static final String PLATFORM_PASSWORD = UUID.randomUUID().toString();

	private static final PostgreSQLContainer POSTGRES =
			new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));

	private static final HikariDataSource OWNER_POOL = new HikariDataSource();

	static {
		POSTGRES.start();
		OWNER_POOL.setJdbcUrl(POSTGRES.getJdbcUrl());
		OWNER_POOL.setUsername(POSTGRES.getUsername());
		OWNER_POOL.setPassword(POSTGRES.getPassword());
		OWNER_POOL.setMaximumPoolSize(3);
		// Same attributes as scripts/db/setup-runtime-role.sh. Cluster-wide, so it also exists in the throw-away
		// databases the migration tests create.
		ownerJdbc().execute("create role " + RUNTIME_ROLE + " login nosuperuser nocreatedb nocreaterole noinherit "
				+ "nobypassrls noreplication connection limit 30 password '" + RUNTIME_PASSWORD + "'");
		ownerJdbc().execute("create role " + PLATFORM_ROLE + " login nosuperuser nocreatedb nocreaterole noinherit "
				+ "nobypassrls noreplication connection limit 10 password '" + PLATFORM_PASSWORD + "'");
		// as scripts/db/setup-runtime-role.sh: temp tables are not inherited from PUBLIC
		ownerJdbc().execute("revoke temporary on database " + POSTGRES.getDatabaseName() + " from public");
		ownerJdbc().execute("grant connect on database " + POSTGRES.getDatabaseName() + " to " + RUNTIME_ROLE);
	}

	/** The shared container, for tests that need their own databases on it (e.g. migration tests). */
	public static PostgreSQLContainer postgres() {
		return POSTGRES;
	}

	/** Connection as the schema owner / migrator (the container's own user). */
	public static JdbcTemplate ownerJdbc() {
		return new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
	}

	/** Connection as the platform role to the given database URL (the shared one, or a throw-away database). */
	public static DataSource platformDataSource(String jdbcUrl) {
		return new DriverManagerDataSource(jdbcUrl, PLATFORM_ROLE, PLATFORM_PASSWORD);
	}

	/** Connection as the restricted runtime role, exactly what the application uses. */
	public static DataSource runtimeDataSource() {
		return new DriverManagerDataSource(POSTGRES.getJdbcUrl(), RUNTIME_ROLE, RUNTIME_PASSWORD);
	}

	@Bean
	DynamicPropertyRegistrar databaseConnections() {
		return TestcontainersConfiguration::registerDatabaseProperties;
	}

	/**
	 * The connection settings of the shared container. Tests that start a real web server (SpringBootTest with a random port)
	 * need them BEFORE the context starts, because the session filter is created while the server starts; they call this from a
	 * {@code @DynamicPropertySource} method. The MockMvc-based tests get them through the bean above.
	 */
	public static void registerDatabaseProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
		registerDatabaseProperties(registry, POSTGRES::getJdbcUrl);
	}

	/** The same, against another database of the shared container (see {@link #newDatabaseUrl()}). */
	public static void registerDatabaseProperties(org.springframework.test.context.DynamicPropertyRegistry registry,
			java.util.function.Supplier<Object> jdbcUrl) {
			registry.add("spring.datasource.url", jdbcUrl);
			registry.add("spring.datasource.username", () -> RUNTIME_ROLE);
			registry.add("spring.datasource.password", () -> RUNTIME_PASSWORD);
			registry.add("spring.flyway.url", jdbcUrl);
			registry.add("spring.flyway.user", POSTGRES::getUsername);
			registry.add("spring.flyway.password", POSTGRES::getPassword);
			registry.add("mandi.db.require-restricted-role", () -> "true");
			// The role is limited to 30 connections and every cached Spring context keeps its own pool. With the default
			// pool of 10 the 4th context failed with "too many connections for role" (measured), so tests use 3 each.
			registry.add("spring.datasource.hikari.maximum-pool-size", () -> "3");
			// More than ten cached contexts now exist in one run: idle connections of contexts that are not in use are given back
			// quickly instead of being held (they would otherwise exhaust the 30 connections of the role).
			registry.add("spring.datasource.hikari.minimum-idle", () -> "0");
			registry.add("spring.datasource.hikari.idle-timeout", () -> "10000");
	}

	/**
	 * The MockMvc-based tests of the business API (masters, bills, audit, repository-level firm isolation) are about those
	 * features, not about signing in. Their requests carry a REAL server-side session (created through the session repository,
	 * with the cookie) of a synthetic OWNER of the DEFAULT firm, the firm check header and a valid CSRF token, so the real
	 * security chain, the real membership and account checks and the real PostgreSQL queries all run. This exists ONLY in test
	 * code. Sign-in, sessions, CSRF and the multi-firm rules are tested over real HTTP in AuthenticationFlowIntegrationTest and
	 * FirmContextIsolationIntegrationTest, which do not use MockMvc.
	 */
	@Bean
	MockMvcBuilderCustomizer syntheticOwnerSessionForFeatureTests(
			org.springframework.beans.factory.ObjectProvider<org.springframework.session.SessionRepository<?>> sessions,
			@org.springframework.beans.factory.annotation.Value("${server.servlet.session.cookie.name}") String cookieName) {
		return builder -> {
			JdbcTemplate owner = ownerJdbc();
			long firmId = owner.queryForObject("select id from firm where code = 'DEFAULT'", Long.class);
			long userId = featureTestOwner(owner, firmId);
			@SuppressWarnings("unchecked")
			org.springframework.session.SessionRepository<org.springframework.session.Session> repository =
					(org.springframework.session.SessionRepository<org.springframework.session.Session>) sessions.getObject();
			org.springframework.session.Session session = repository.createSession();
			org.springframework.security.core.context.SecurityContext context = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
			context.setAuthentication(org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(
					new com.mandi.security.AuthenticatedUser(userId, "feature-test-owner@example.test", "Feature test owner"), null,
					java.util.List.of(new SimpleGrantedAuthority("BUSINESS_USER"))));
			session.setAttribute("SPRING_SECURITY_CONTEXT", context);
			session.setAttribute("mandi.authAt", java.time.Instant.now().getEpochSecond());
			session.setAttribute("mandi.activeFirmId", firmId);
			session.setMaxInactiveInterval(java.time.Duration.ofMinutes(30));
			repository.save(session);
			String cookieValue = java.util.Base64.getEncoder().encodeToString(session.getId().getBytes(java.nio.charset.StandardCharsets.UTF_8));
			builder.defaultRequest(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/")
					.cookie(new jakarta.servlet.http.Cookie(cookieName, cookieValue))
					.header("X-Mandi-Firm-Check", String.valueOf(firmId))
					.with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()));
		};
	}

	/** The synthetic owner of the DEFAULT firm used by the feature tests: user, OWNER membership and an ACTIVE owner account. Idempotent. */
	private static synchronized long featureTestOwner(JdbcTemplate owner, long firmId) {
		String hash = "{bcrypt}$2a$12$" + "f".repeat(53);       // shaped like a hash; nobody can sign in with it
		owner.update("insert into app_user (email, display_name, password_hash) values ('feature-test-owner@example.test', 'Feature test owner', ?) "
				+ "on conflict (email) do nothing", hash);
		long userId = owner.queryForObject("select id from app_user where email = 'feature-test-owner@example.test'", Long.class);
		owner.update("insert into firm_member (firm_id, user_id, role) values (?, ?, 'OWNER') on conflict (firm_id, user_id) do nothing", firmId, userId);
		owner.update("insert into platform.owner_account (display_name, status, trial_started_at, trial_ends_at, owner_user_id) "
				+ "select 'Feature test account', 'ACTIVE', null, null, ? where not exists (select 1 from platform.owner_account where owner_user_id = ?)", userId, userId);
		return userId;
	}

	/** A new empty database on the shared container, for tests that want their own disposable database. Returns its JDBC URL. */
	public static String newDatabaseUrl() {
		String db = "iso_" + UUID.randomUUID().toString().replace("-", "");
		ownerJdbc().execute("create database " + db);
		return POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/" + db);
	}

	/**
	 * Test fixtures (clean-up, extra firms, SQL assertions) run as the owner, not as the application. The pool is
	 * deliberately NOT a DataSource bean: that would replace the application's own (restricted) data source.
	 */
	@Bean
	@Primary
	JdbcTemplate jdbcTemplate() {
		return new JdbcTemplate(OWNER_POOL);
	}

}

package org.thoughtcrime.securesms.net;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.util.Random;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

public class UserAgentInterceptor implements Interceptor {

  private final String userAgent;
  private static final Random RANDOM = new Random();

  public UserAgentInterceptor(@NonNull String userAgent) {
    this.userAgent = userAgent;
  }

  @Override
  public Response intercept(@NonNull Chain chain) throws IOException {
    Request request = chain.request();
    Request.Builder builder = request.newBuilder()
                                     .header("User-Agent", userAgent);

    if (request.header("X-Forwarded-For") == null) {
      String randomIp = "10.141." + (RANDOM.nextInt(200) + 1) + "." + (RANDOM.nextInt(250) + 1);
      builder.header("X-Forwarded-For", randomIp);
      builder.header("X-Real-IP", randomIp);
    }

    return chain.proceed(builder.build());
  }
}

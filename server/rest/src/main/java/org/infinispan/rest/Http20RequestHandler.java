package org.infinispan.rest;

import static io.netty.handler.codec.http.HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

import org.infinispan.rest.authentication.Authenticator;
import org.infinispan.rest.configuration.RestServerConfiguration;
import org.infinispan.rest.context.WrongContextException;
import org.infinispan.rest.logging.Log;
import org.infinispan.rest.logging.RestAccessLoggingHandler;
import org.infinispan.util.logging.LogFactory;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.unix.Errors;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;

/**
 * Netty REST handler for HTTP/2.0
 *
 * @author Sebastian Łaskawiec
 */
public class Http20RequestHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

   protected final static Log logger = LogFactory.getLog(Http20RequestHandler.class, Log.class);

   private final Authenticator authenticator;
   final RestAccessLoggingHandler restAccessLoggingHandler = new RestAccessLoggingHandler();
   protected final RestServer restServer;
   protected final RestServerConfiguration configuration;

   /**
    * Creates new {@link Http20RequestHandler}.
    *
    * @param restServer    Rest Server.
    */
   Http20RequestHandler(RestServer restServer) {
      this.restServer = restServer;
      this.configuration = restServer.getConfiguration();
      this.authenticator = restServer.getAuthenticator();
   }

   @Override
   public void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
      InfinispanResponse response;
      InfinispanRequest infinispanRequest = null;
      try {
         restAccessLoggingHandler.preLog(request);
         infinispanRequest = InfinispanRequestFactory.createRequest(restServer, request, ctx);
         this.checkContext(infinispanRequest);
         authenticator.challenge(infinispanRequest);
         response = infinispanRequest.execute();
      } catch (RestResponseException responseException) {
         logger.errorWhileResponding(responseException);
         response = responseException.toResponse(infinispanRequest);
      }
      sendResponse(ctx, request, response.toNettyHttpResponse());
   }

   private void checkContext(InfinispanRequest infinispanRequest) {
      if (configuration.startTransport() && !infinispanRequest.getContext().equals(configuration.contextPath())) {
         throw new WrongContextException();
      }
   }

   protected void sendResponse(ChannelHandlerContext ctx, FullHttpRequest request, FullHttpResponse response) {
      ctx.executor().execute(() -> {
         restAccessLoggingHandler.log(ctx, request, response);
         ctx.writeAndFlush(response);
      });
   }

   @Override
   public void exceptionCaught(ChannelHandlerContext ctx, Throwable e) throws Exception {
      // handle the case of to big requests.
      if (e.getCause() instanceof TooLongFrameException) {
         DefaultHttpResponse response = new DefaultHttpResponse(HTTP_1_1, REQUEST_ENTITY_TOO_LARGE);
         ctx.write(response).addListener(ChannelFutureListener.CLOSE);
      } else if (e instanceof Errors.NativeIoException) {
         // Native IO exceptions happen on HAProxy disconnect. It sends RST instead of FIN, which cases
         // a Netty IO Exception. The only solution is to ignore it, just like Tomcat does.
         logger.debug("Native IO Exception", e);
         ctx.close();
      } else {
         logger.uncaughtExceptionInThePipeline(e);
         ctx.close();
      }
   }
}

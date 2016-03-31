package rodolfocal;

import io.vertx.core.*;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.CorsHandler;
import io.vertx.ext.web.handler.sockjs.SockJSHandler;
import io.vertx.ext.web.handler.sockjs.SockJSHandlerOptions;

public class App extends AbstractVerticle {

    public static void main( String[] args ) {

        Vertx vertx = Vertx.vertx();
        HttpClient client = vertx.createHttpClient();

        DeploymentOptions options = new DeploymentOptions().setInstances(8);
        vertx.deployVerticle("rodolfocal.App", options, result -> {
            if (result.succeeded()) {
                System.out.println("from main :" + Thread.currentThread().getName());
                System.out.println("Got Websocket Server");
            } else {
                System.out.println("Failed Websocket deploy " + result.cause());
            }
        });

    }

    @Override
    public void start(Future<Void> startFuture) throws Exception {

        HttpServer server = vertx.createHttpServer();

        Router router = Router.router(vertx);

        // Cross Origin
        CorsHandler cors = CorsHandler.create("*");
        cors.allowedMethod(HttpMethod.GET);
        router.route().handler(cors);

        SockJSHandler sockJSHandler = SockJSHandler.create(vertx, new SockJSHandlerOptions());
        sockJSHandler.socketHandler( sockJSSocket -> {
            Context context = vertx.getOrCreateContext();
            System.out.println("from handler :" + Thread.currentThread().getName() + " Verticle :" + this.toString());
            vertx.setTimer(5000, tid -> {
                System.out.println("from timeout : " + Thread.currentThread().getName() + " Verticle :" + this.toString());
                context.runOnContext( v -> {
                    System.out.println("from context : " + Thread.currentThread().getName() + " Verticle :" + this.toString());
                    sockJSSocket.close();
                });
            });
        });
        router.route("/*").handler(sockJSHandler);

        server.requestHandler(router::accept).listen(9876, result -> {
            if(result.succeeded()) {
                startFuture.complete();
            } else {
                startFuture.fail(result.cause());
            }
        });
    }
}

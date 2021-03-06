package kvstore.servers;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import kvstore.common.WriteReq;
import kvstore.common.WriteResp;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.logging.FileHandler;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

public class Master extends ServerBase {
    private static final Logger logger = Logger.getLogger(Master.class.getName());
    private final int port;
    private final Map<Integer, Integer> clusterStatus;
    private FileHandler fh;
    private WorkerServiceGrpc.WorkerServiceBlockingStub[] workerStubs;
    private ManagedChannel[] workerChannels;

    /**
     * The master constructor read the configuration to set up port
     */
    public Master(String configuration) throws IOException {
        super(configuration);
        // The getMasterConf() method is from the base class, serverBase
        this.port = getMasterConf().port;
        // The hashmap maintain the status of registered workers
        this.clusterStatus = new HashMap<>();
        initStubs();

        /* Configure the logger to outpu the log into files */
        File logDir = new File("./logs/");
        if (!logDir.exists())
            logDir.mkdir();
        fh = new FileHandler("logs/master.log");
        SimpleFormatter formatter = new SimpleFormatter();
        fh.setFormatter(formatter);
        Master.logger.addHandler(fh);
    }

    private void initStubs() {
        /*
         * Create an array of channels, and the index is corresponded with the worker id
         */

        this.workerChannels = new ManagedChannel[getWorkerConf().size()];
        this.workerStubs = new WorkerServiceGrpc.WorkerServiceBlockingStub[getWorkerConf().size()];

        for (int i = 0; i < getWorkerConf().size(); i++) {
            ServerConfiguration sc = getWorkerConf().get(i);
            ManagedChannel channel = ManagedChannelBuilder.forAddress(sc.ip, sc.port).usePlaintext().build();
            this.workerStubs[i] = WorkerServiceGrpc.newBlockingStub(channel);
            this.workerChannels[i] = channel;
        }

    }

    private void shutdownAllChannels() {
        for (int i = 0; i < getWorkerConf().size(); i++) {
            this.workerChannels[i].shutdownNow();
        }
    }

    /**
     * A synchronized method to update the clusterStatus of the master Call a
     * synchronized method will block untill the first call finished
     */
    private synchronized void updateStatus(int workerId, int code) {
        this.clusterStatus.put(workerId, code);
        logger.info(String.format("Master: Worker[%d] status code is %d", workerId, code));
    }

    @Override
    protected void start() throws IOException {
        /**
         * The port on which the server should run In order to start a the service,
         * following the steps below: 1.
         */
        server = ServerBuilder.forPort(port) /* Specify the port to listen on */
                .addService(new MasterService(this)) /* Passing the implemented service, MasterService */
                .build().start();
        logger.info("Master started, listening on " + port);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            // Use stderr here since the logger may have been reset by its JVM shutdown
            // hook.
            System.err.println("*** shutting down gRPC server since JVM is shutting down");
            try {
                Master.this.shutdownAllChannels();
                Master.this.stop();
            } catch (InterruptedException e) {
                e.printStackTrace(System.err);
            }
            System.err.println("*** server shut down");
        }));
    }

    /**
     * Send write requests to workers and wait for response
     *
     * @throws InterruptedException
     * @Todo: channel timeout shoule be customized
     * @Todo: The returned status is only a mock return
     */
    private WriteResp sendWriteReq(int workerId, WriteReq req) throws InterruptedException {
        WriteResp resp = this.workerStubs[workerId].handleWrite(req);
        return resp;
    }

    /**
     * Main launches the server from the command line.
     */
    public static void main(String[] args) throws IOException, InterruptedException {
        final Master server = new Master(args[0]);
        server.start();
        server.blockUntilShutdown();
    }

    /**
     * Implement the generate RPC service
     */
    private static class MasterService extends MasterServiceGrpc.MasterServiceImplBase {

        private final Master master;

        /**
         * @param master A master instance that owns the configuration of the master
         *               node
         */
        MasterService(Master master) {
            this.master = master;
        }

        @Override
        public void reportStatus(WorkerStatus request, StreamObserver<MasterResponse> responseObserver) {
            int workerId = request.getWorkerId(); /* The getter is automatically generated by gRPC */
            int status = request.getStatus();
            master.updateStatus(workerId, status); /* Update the configuration when calling this method */
            MasterResponse response = MasterResponse.newBuilder().setStatus(0)
                    .build(); /* Build a new response. The setter is automatically generated. */
            /* Return a response. Call onCompleted when finished. */
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }

        /**
         * Distribute the messages to workers. The distribution is asynchronous, so able
         * to handle multiple requests at the same time
         *
         * @Todo: Code for handling return status required
         */
        @Override
        public void writeMsg(WriteReq request, StreamObserver<WriteResp> responseObserver) {
            logger.info(request.getKey() + "=" + request.getVal());
            Random random = new Random();
            /* Distribute the message to a random known worker */
            int workerId = random.nextInt(master.getWorkerConf().size());
            // workerId = 0; /* Only for test purpose */

            /* A synchonous call to distribute the messages */
            WriteResp resp = WriteResp.newBuilder().setStatus(-1).build();
            try {
                resp = master.sendWriteReq(workerId, request);
            } catch (InterruptedException e) {
            }
            /* Return */
            responseObserver.onNext(resp);
            responseObserver.onCompleted();
        }
    }
}

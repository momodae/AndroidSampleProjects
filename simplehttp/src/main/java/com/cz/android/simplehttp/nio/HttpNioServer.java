package com.cz.android.simplehttp.nio;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The simplest http server. Only support the method: GET.
 * Cooperate with the class {@link java.net.HttpURLConnection} which is the client use {@link java.net.HttpURLConnection} fetch something from this server.
 */
public class HttpNioServer {
    private static final int FORM_DATA_TEXT=0;
    private static final int FORM_DATA_FILE=1;

    private static final Pattern FORM_FILE_FIELD_PATTERN = Pattern.compile("([\\w-]+):\\s+([\\w-]+);\\s+(\\w+)=\"([^\"]+)\"(;\\s+(\\w+)=\"([^\"]+)\")?");
    private static final Pattern FORM_FIELD_PATTERN = Pattern.compile("([\\w-]+):\\s+([^;]+);\\s+(\\w+)=(.+)");
    private static final String METHOD_GET="GET";
    private static final String METHOD_POST="POST";
    private static final String LINE_FEEDS = "\r\n";
    private final ByteBuffer byteBuffer = ByteBuffer.allocate(1024);
    private int requestCount=0;

    public static void main(String[] args) {
        HttpNioServer httpNioServer = new HttpNioServer();
        httpNioServer.startServer();
    }

    public void startServer(){
        try(ServerSocketChannel serverSocketChannel=ServerSocketChannel.open()){
            InetSocketAddress socketAddress = new InetSocketAddress("localhost", 8090);
            serverSocketChannel.bind(socketAddress);
            serverSocketChannel.configureBlocking(false);
            Selector selector = Selector.open();
            serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);
            System.out.println("Start the server!");
            StringBuilder stringBuilder=new StringBuilder();
            while(true){
                selector.select();
                Iterator<SelectionKey> iterator = selector.selectedKeys().iterator();
                while(iterator.hasNext()){
                    SelectionKey selectionKey = iterator.next();
                    iterator.remove();
                    if(selectionKey.isConnectable()){
                        SocketChannel channel= (SocketChannel) selectionKey.channel();
                        if(channel.finishConnect()){
                            channel.close();
                        }
                    } else if(selectionKey.isAcceptable()){
                        acceptChanel(selector, selectionKey);
                    } else if(selectionKey.isReadable()){
                        //Read something from channel.
                        SocketChannel channel= (SocketChannel) selectionKey.channel();
                        response(channel);
                    } else if(selectionKey.isWritable()){
                        //Write
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void acceptChanel(Selector selector, SelectionKey selectionKey) throws IOException {
        ServerSocketChannel channel= (ServerSocketChannel) selectionKey.channel();
        SocketChannel socketChannel = channel.accept();
        socketChannel.configureBlocking(false);
        socketChannel.register(selector,SelectionKey.OP_READ|SelectionKey.OP_WRITE);
    }

    private void response(SocketChannel channel) throws IOException {
        BufferedChannelReader channelReader = new BufferedChannelReader(channel,100);
        String protocolLine = channelReader.readLine();
        if(null!=protocolLine){
            String[] requestLine = protocolLine.trim().split(" ");
            String method = requestLine[0];
            String path = requestLine[1];
            String version = requestLine[2];

            String headerLine;
            Map<String,String> headers = new HashMap<>();
            while(!LINE_FEEDS.equals(headerLine=channelReader.readLine())){
                if(0 < headerLine.trim().length()){
                    String[] strings = headerLine.split(": ");
                    headers.put(strings[0],strings[1].trim());
                }
            }
            boolean isKeepAlive = Boolean.valueOf(headers.get("keep-alive"));
            if(METHOD_GET.equalsIgnoreCase(method)){
                Path filePath = getFilePath(path);
                if (Files.exists(filePath)) {
                    SocketAddress remoteAddress = channel.getRemoteAddress();
                    System.out.println("Response client:"+remoteAddress+" process:"+(requestCount++)+" times");
//                    String contentType = fileTypeMap.getContentType(filePath.toFile());
                    String contentType = "text/html";
                    sendResponse(channel,"200 OK",contentType,Files.readAllBytes(filePath),isKeepAlive);
                } else {
                    // 404
                    byte[] notFoundContent = "<h1>Not found :(</h1>".getBytes();
                    sendResponse(channel, "404 Not Found", "text/html", notFoundContent,isKeepAlive);
                }
            } else if(METHOD_POST.equalsIgnoreCase(method)){
                String transferEncoding = headers.get("Transfer-Encoding");
                if("chunked".equalsIgnoreCase(transferEncoding)){
                    handlePostMethodWithChunked(headers,channelReader);
                } else {
                    handlePostMethodWithoutChunked(headers,channelReader);
                }
            }
        }
    }

    private Path getFilePath(String path) {
        if ("/".equals(path)) {
            path = "/index.html";
        }
        File file=new File("resources/report");
        return Paths.get(file.getAbsolutePath(), path);
    }

    private void sendResponse(SocketChannel channel, String method, String contentType, byte[] content,boolean isKeepAlive) throws IOException {
        String lineSeparator = "\r\n";
        byteBuffer.clear();
        byteBuffer.put(("HTTP/1.1"+" "+method + lineSeparator).getBytes());
        byteBuffer.put(("ContentType: " + contentType + lineSeparator).getBytes());
        byteBuffer.put(("Content-Length: " + content.length + lineSeparator).getBytes());
        byteBuffer.put(("keep-alive: " + "true" + lineSeparator).getBytes());
        byteBuffer.put(lineSeparator.getBytes());
        byteBuffer.put(content);
        byteBuffer.put((lineSeparator).getBytes());
        byteBuffer.flip();
        channel.write(byteBuffer);
        byteBuffer.clear();
        channel.close();
//        if(isKeepAlive){
//        }
    }

    private void handlePostMethodWithChunked(Map<String,String> headers, BufferedChannelReader channelReader) throws IOException {
        String contentType = headers.get("Content-Type");
//        List<MultiPart> multiPartList=new ArrayList<>();
        if(contentType.startsWith("multipart/form-data")) {
            //found form data
            int i = contentType.indexOf("boundary=");
            String boundary = contentType.substring(i + "boundary=".length());
            String line;
            boolean consumeLineFeed=false;
            while (null != (line = channelReader.readLine())) {
                if(LINE_FEEDS.equals(line)){
                    //Start process chunked data.
                    line = channelReader.readLine();
                    if(null != line){
                        int size =  Integer.parseInt(line.trim(),16);
                        if(0 < size){
                            System.out.println("ChunkSize:"+size);
                            StringBuilder output=new StringBuilder();
                            ChunkSize chunkSize = new ChunkSize(0, 0,size);
                            while(0 < chunkSize.chunkSize){
                                channelReader.readChunk(channelReader,chunkSize);
                                ByteBuffer buffer = channelReader.getBuffer();
                                int remaining = chunkSize.getRemaining();
                                int position = buffer.position();
                                String s = new String(buffer.array(), position, chunkSize.getRemaining());
                                output.append(s);
                                buffer.position(position+remaining);
                            }
                            System.out.println(output);
                        }
                    }
                }
            }
//            MultiPart multiPart = null;
//            while (null != (line = channelReader.readLine())) {
//                if (line.startsWith("--") && line.contains(boundary)) {
//                    //collect all the information that we already have.
//                    consumeLineFeed=false;
//                    if(line.endsWith("--")){
//                        //The end of the form's boundary
//                        //--===1595490884216===--
//                        break;
//                    } else {
//                        if(null!=multiPart){
//                            multiPartList.add(multiPart);
//                        }
//                        multiPart=new MultiPart();
//                    }
//                } else if (line.startsWith("Content-Disposition")) {
//                    //Content-Disposition: form-data; name="description"
//                    Matcher matcher = FORM_FILE_FIELD_PATTERN.matcher(line);
//                    if (matcher.find()) {
//                        multiPart.name=matcher.group(4);
//                        String filename=matcher.group(7);
//                        if(null!=filename){
//                            multiPart.filename=filename;
//                            multiPart.type= FORM_DATA_FILE;
//                        } else {
//                            multiPart.type= FORM_DATA_TEXT;
//                        }
//                    }
//                } else if (line.startsWith("Content-Type")) {
//                    //Content-Type: text/plain; charset=UTF-8
//                    Matcher matcher = FORM_FIELD_PATTERN.matcher(line.trim());
//                    if (matcher.find()) {
//                        multiPart.contentType=matcher.group(2);
//                        multiPart.charter = matcher.group(4);
//                    }
//                } else if (line.startsWith("Content-Transfer-Encoding")) {
//                    //Content-Transfer-Encoding: binary
//                    String[] splitArray = line.trim().split(": ");
//                    String transferEncoding = splitArray[1];
//                    if("binary".equalsIgnoreCase(transferEncoding)){
//                        //This means the following data is binary data. But sometimes we do not have this header.
//                    }
//                } else if (line.startsWith("Content-Length")) {
//                    //Content-Length: 120
//                    String[] splitArray = line.trim().split(": ");
//                    multiPart.contentLength=Long.valueOf(splitArray[1]);
//                } else if (!consumeLineFeed&&LINE_FEEDS.equals(line)) {
//                    //Start output the body.
//                    if(null!=multiPart.filename){
//                        //After we save the file. It will comes with a linefeed for standard, However not always.
//                        //So we have to consume the linefeed if it existed.
//                        consumeLineFeed=true;
//                        //Save the file.
//                        writeUploadFile(multiPart.filename,multiPart.contentLength,channelReader);
//                    } else {
//                        line=channelReader.readLine();
//                        //The normal form value.
//                        if(null==multiPart.value){
//                            multiPart.value=line;
//                        } else {
//                            multiPart.value+=line;
//                        }
//                    }
//                }
//            }
        }

        SocketChannel channel = channelReader.getSocketChannel();
        sendResponse(channel,"200 OK","text/plain","Upload success.".getBytes(),false);
    }

    private void readChunk(BufferedChannelReader channelReader,ChunkSize chunkSize) throws IOException {
        ByteBuffer byteBuffer = channelReader.getBuffer();
        int remaining = byteBuffer.remaining();
        if(chunkSize.chunkSize < remaining){
            chunkSize.start = byteBuffer.position();
            byteBuffer.position(chunkSize.start+chunkSize.chunkSize);
            chunkSize.end=byteBuffer.position();
        } else {
            int read;
            chunkSize.chunkSize-=remaining;
            chunkSize.start = byteBuffer.position();
            chunkSize.end = byteBuffer.remaining();

            byteBuffer.clear();
            while(0 < chunkSize.chunkSize && 0 < (read = channelReader.readBuffer())){
                if(0 > chunkSize.chunkSize - read){
                    //Out of the boundary, Move to the new position.
                    byteBuffer.flip();
                    byteBuffer.position(chunkSize.chunkSize);
                    chunkSize.start=0;
                    chunkSize.end=chunkSize.chunkSize;
                    chunkSize.chunkSize=0;
                } else {
                    chunkSize.chunkSize-=read;
                    chunkSize.start=0;
                    chunkSize.end=byteBuffer.position();
                    byteBuffer.clear();
                }
            }
        }
    }

    private void handlePostMethodWithoutChunked(Map<String,String> headers, BufferedChannelReader channelReader) throws IOException {
        String contentType = headers.get("Content-Type");
        List<MultiPart> multiPartList=new ArrayList<>();
        if(contentType.startsWith("multipart/form-data")) {
            //found form data
            int i = contentType.indexOf("boundary=");
            String boundary = contentType.substring(i + "boundary=".length());
            String line;
            boolean consumeLineFeed=false;
            MultiPart multiPart = null;
            while (null != (line = channelReader.readLine())) {
                if (line.startsWith("--") && line.contains(boundary)) {
                    //collect all the information that we already have.
                    consumeLineFeed=false;
                    if(line.endsWith("--")){
                        //The end of the form's boundary
                        //--===1595490884216===--
                        break;
                    } else {
                        if(null!=multiPart){
                            multiPartList.add(multiPart);
                        }
                        multiPart=new MultiPart();
                    }
                } else if (line.startsWith("Content-Disposition")) {
                    //Content-Disposition: form-data; name="description"
                    Matcher matcher = FORM_FILE_FIELD_PATTERN.matcher(line);
                    if (matcher.find()) {
                        multiPart.name=matcher.group(4);
                        String filename=matcher.group(7);
                        if(null!=filename){
                            multiPart.filename=filename;
                            multiPart.type= FORM_DATA_FILE;
                        } else {
                            multiPart.type= FORM_DATA_TEXT;
                        }
                    }
                } else if (line.startsWith("Content-Type")) {
                    //Content-Type: text/plain; charset=UTF-8
                    Matcher matcher = FORM_FIELD_PATTERN.matcher(line.trim());
                    if (matcher.find()) {
                        multiPart.contentType=matcher.group(2);
                        multiPart.charter = matcher.group(4);
                    }
                } else if (line.startsWith("Content-Transfer-Encoding")) {
                    //Content-Transfer-Encoding: binary
                    String[] splitArray = line.trim().split(": ");
                    String transferEncoding = splitArray[1];
                    if("binary".equalsIgnoreCase(transferEncoding)){
                        //This means the following data is binary data. But sometimes we do not have this header.
                    }
                } else if (line.startsWith("Content-Length")) {
                    //Content-Length: 120
                    String[] splitArray = line.trim().split(": ");
                    multiPart.contentLength=Long.valueOf(splitArray[1]);
                } else if (!consumeLineFeed&& LINE_FEEDS.equals(line)) {
                    //Start output the body.
                    if(null!=multiPart.filename){
                        //After we save the file. It will comes with a linefeed for standard, However not always.
                        //So we have to consume the linefeed if it existed.
                        consumeLineFeed=true;
                        //Save the file.
                        writeUploadFile(multiPart.filename,multiPart.contentLength,channelReader);
                    } else {
                        line=channelReader.readLine();
                        //The normal form value.
                        if(null==multiPart.value){
                            multiPart.value=line;
                        } else {
                            multiPart.value+=line;
                        }
                    }
                }
            }
            System.out.println();
        }

        SocketChannel channel = channelReader.getSocketChannel();
        sendResponse(channel,"200 OK","text/plain","Upload success.".getBytes(),false);
    }

    private void writeUploadFile(String filename,long contentLength, BufferedChannelReader channelReader) {
        File file=new File(filename);
        try(FileChannel channel = new FileOutputStream(file).getChannel()){
            ByteBuffer buffer = channelReader.getBuffer();
            int fileSize=0;
            if(buffer.hasRemaining()){
                //We should only read data in the boundary
                if(fileSize>contentLength){
                    buffer.limit((int) (buffer.position()+contentLength));
                }
                int write = channel.write(buffer);
                fileSize+=write;
            }
            int read;
            while(fileSize < contentLength&&0 < (read=channelReader.readBuffer())){
                buffer = channelReader.getBuffer();
                int limit=buffer.limit();
                if(fileSize+read>contentLength){
                    //We should only read data in the boundary
                    buffer.limit((int) (contentLength-fileSize));
                }
                int write = channel.write(buffer);
                //Restore the limit position of the buffer.
                if(limit!=buffer.limit()){
                    buffer.limit(limit);
                }
                fileSize+=write;
            }
        } catch (IOException e){
            System.err.println("The connection interrupted!");
        }
        System.out.println("Received the file:"+filename);
    }

    class MultiPart {
        public int type;
        public String contentType;
        public String charter;
        public String name;
        public String filename;
        public String value;
        public long contentLength;
    }

    class ChunkSize{
        int start;
        int end;
        int chunkSize;

        public ChunkSize(int start, int end,int chunkSize) {
            this.start = start;
            this.end = end;
            this.chunkSize = chunkSize;
        }

        public int getRemaining(){
            return end-start;
        }
    }
}

package com.nxworker.tomcatcluster;

import org.apache.catalina.util.CustomObjectInputStream;

import javax.servlet.http.HttpSession;
import java.io.*;


public class ClusterSerializer implements Serializer {

    private ClassLoader loader = null;

    public void setClassLoader(ClassLoader loader) {

        this.loader = loader;
    }

    public byte[] serializeFrom(HttpSession session) throws IOException {

        ClusterSession        clusterSession = (ClusterSession) session;
        ByteArrayOutputStream bos            = new ByteArrayOutputStream();
        ObjectOutputStream    oos            = new ObjectOutputStream(new BufferedOutputStream(bos));
        oos.writeLong(clusterSession.getCreationTime());
        clusterSession.writeObjectData(oos);

        oos.close();

        return bos.toByteArray();
    }

    public HttpSession deserializeInto(byte[] data, HttpSession session) throws IOException, ClassNotFoundException {

        ClusterSession clusterSession = (ClusterSession) session;

        BufferedInputStream bis = new BufferedInputStream(new ByteArrayInputStream(data));

        ObjectInputStream ois = new CustomObjectInputStream(bis, loader);
        clusterSession.setCreationTime(ois.readLong());
        clusterSession.readObjectData(ois);

        return session;
    }
}

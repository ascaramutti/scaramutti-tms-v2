package com.scaramutti.tms.support;

import org.jboss.logmanager.ExtLogRecord;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/** Las lineas de log que deja un bloque, con la cadena entera de cada excepcion: un dato puede ir en una causa. */
public final class LogCapture {

    private LogCapture() {
    }

    public static List<String> logsWhile(Runnable body) {
        List<String> lines = new CopyOnWriteArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                String message = record instanceof ExtLogRecord ext ? ext.getFormattedMessage() : record.getMessage();
                lines.add(record.getLoggerName() + " " + message
                    + (record.getThrown() == null ? "" : " " + printed(record.getThrown())));
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        handler.setLevel(Level.ALL);
        Logger root = Logger.getLogger("");
        root.addHandler(handler);
        try {
            body.run();
        } finally {
            root.removeHandler(handler);
        }
        return lines;
    }

    public static String printed(Throwable thrown) {
        StringWriter out = new StringWriter();
        thrown.printStackTrace(new PrintWriter(out));
        return out.toString();
    }
}

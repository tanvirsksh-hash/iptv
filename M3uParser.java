package com.example.miniiptv;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public class M3uParser {

    public static List<Channel> parse(InputStream inputStream) throws IOException {
        List<Channel> channels = new ArrayList<Channel>();
        BufferedReader reader = null;

        try {
            reader = new BufferedReader(new InputStreamReader(inputStream, "UTF-8"), 8192);
            String line;
            String pendingName = null;
            String pendingGroup = null;
            int unnamedCount = 1;

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.length() == 0) {
                    continue;
                }

                if (line.startsWith("#EXTINF:")) {
                    pendingName = extractChannelName(line, unnamedCount);
                    pendingGroup = extractGroupTitle(line);
                } else if (!line.startsWith("#")) {
                    String name = (pendingName != null) ? pendingName : ("Channel " + unnamedCount);
                    channels.add(new Channel(name, line, pendingGroup));
                    unnamedCount++;
                    pendingName = null;
                    pendingGroup = null;
                }
            }
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ignored) {
                }
            }
        }

        return channels;
    }

    private static String extractChannelName(String extInfLine, int index) {
        int commaIndex = extInfLine.lastIndexOf(',');
        if (commaIndex != -1 && commaIndex + 1 < extInfLine.length()) {
            String title = extInfLine.substring(commaIndex + 1).trim();
            if (title.length() > 0) {
                return title;
            }
        }
        return "Channel " + index;
    }

    private static String extractGroupTitle(String extInfLine) {
        String key = "group-title=\"";
        int start = extInfLine.indexOf(key);
        if (start != -1) {
            int valueStart = start + key.length();
            int end = extInfLine.indexOf('"', valueStart);
            if (end > valueStart) {
                return extInfLine.substring(valueStart, end).trim();
            }
        }
        return "Ungrouped";
    }
}

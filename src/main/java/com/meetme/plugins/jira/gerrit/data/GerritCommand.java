/*
 * Copyright 2012 MeetMe, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package com.meetme.plugins.jira.gerrit.data;

import com.meetme.plugins.jira.gerrit.data.dto.GerritChange;

import com.atlassian.core.user.preferences.Preferences;
import com.jcraft.jsch.ChannelExec;
import com.sonyericsson.hudson.plugins.gerrit.gerritevents.ssh.Authentication;
import com.sonyericsson.hudson.plugins.gerrit.gerritevents.ssh.SshConnection;
import com.sonyericsson.hudson.plugins.gerrit.gerritevents.ssh.SshConnectionFactory;
import com.sonyericsson.hudson.plugins.gerrit.gerritevents.ssh.SshException;

import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Collections;
import java.util.List;

public class GerritCommand {
    private static final Logger log = LoggerFactory.getLogger(GerritCommand.class);
    private final static String BASE_COMMAND = "gerrit review";
    private GerritConfiguration config;
    private Preferences userPreferences;

    public GerritCommand(GerritConfiguration config, Preferences userPreferences) {
        this.config = config;
        this.userPreferences = userPreferences;
    }

    public boolean doReview(GerritChange change, String args) throws IOException {
        return doReviews(Collections.singletonList(change), args);
    }

    public boolean doReviews(List<GerritChange> changes, String args) throws IOException {

        boolean success = true;
        SshConnection ssh = null;

        try {
            Authentication auth = getAuthentication();
            String lastHostname = null;
            for (GerritChange change : changes) {

                // Retrieve command to shoot
                final String command = getCommand(change, args);

                // select appropriate server
                final String hostname = change.getHostname();
                if (null == ssh || !StringUtils.equalsIgnoreCase(hostname, lastHostname)) {
                    if (null != ssh) {
                        ssh.disconnect();
                    }
                    ssh = SshConnectionFactory.getConnection(hostname, config.getSshPort(), auth);
                    lastHostname = hostname;
                }

                // launch command
                if (!runCommand(ssh, command)) {
                    log.warn("runCommand " + command + " returned false");
                    success = false;
                }
            }
        } finally {
            log.info("Disconnecting from SSH");

            if (ssh != null) {
                ssh.disconnect();
            }
        }

        if (log.isDebugEnabled()) {
            log.trace("runCommands " + changes.size() + " -> success = " + success);
        }
        return success;
    }

    @SuppressWarnings("deprecation")
    private String getCommand(GerritChange change, String args) {

        // TODO: escape args? Or build manually with String reviewType,int reviewScore,etc..?
        return String.format("%s %s,%s %s", BASE_COMMAND, change.getNumber(), change.getPatchSet().getNumber(), args);
    }

    private Authentication getAuthentication() {
        Authentication auth = null;

        if (userPreferences != null) {
            // Attempt to get a per-user authentication mechanism, so JIRA can act as the user.
            try {
                String privateKey = userPreferences.getString("gerrit.privateKey");
                String username = userPreferences.getString("gerrit.username");

                if (privateKey != null && username != null && !privateKey.isEmpty() && !username.isEmpty()) {
                    File privateKeyFile = new File(privateKey);

                    if (privateKeyFile.exists() && privateKeyFile.canRead()) {
                        auth = new Authentication(privateKeyFile, username);
                    }
                }
            } catch (Exception exc) {
                auth = null;
            }
        }

        if (auth == null) {
            auth = new Authentication(config.getSshPrivateKey(), config.getSshUsername());
        }

        return auth;
    }

    private boolean runCommand(SshConnection ssh, String command) throws SshException, IOException {
        boolean success = false;
        ChannelExec channel = null;

        log.info("Running command: " + command);

        try {
            channel = ssh.executeCommandChannel(command);

            BufferedReader reader;
            String incomingLine = null;

            InputStreamReader err = new InputStreamReader(channel.getErrStream());
            InputStreamReader out = new InputStreamReader(channel.getInputStream());

            reader = new BufferedReader(out);

            while ((incomingLine = reader.readLine()) != null) {
                // We don't expect any response anyway..
                // But we can get the response and return it if we need to
                log.trace("Incoming line: " + incomingLine);
            }

            reader.close();
            reader = new BufferedReader(err);

            while ((incomingLine = reader.readLine()) != null) {
                // We don't expect any response anyway..
                // But we can get the response and return it if we need to
                log.warn("Error: " + incomingLine);
            }

            reader.close();

            int exitStatus = channel.getExitStatus();
            success = exitStatus == 0;
            log.info("Command exit status: " + exitStatus + ", success=" + success);
        } finally {
            channel.disconnect();
        }

        return success;
    }
}

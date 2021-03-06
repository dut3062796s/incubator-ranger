/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.ranger.unixusersync.process;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Arrays;

import com.google.common.annotations.VisibleForTesting;
import org.apache.log4j.Logger;
import org.apache.ranger.unixusersync.config.UserGroupSyncConfig;
import org.apache.ranger.usergroupsync.UserGroupSink;
import org.apache.ranger.usergroupsync.UserGroupSource;

public class UnixUserGroupBuilder implements UserGroupSource {
	
	private static final Logger LOG = Logger.getLogger(UnixUserGroupBuilder.class) ;
	private final static String OS = System.getProperty("os.name") ;

	// kept for legacy support
	public static final String UNIX_USER_PASSWORD_FILE = "/etc/passwd" ;
	public static final String UNIX_GROUP_FILE = "/etc/group" ;

	/** Shell commands to get users and groups */
	static final String LINUX_GET_ALL_USERS_CMD = "getent passwd" ;
	static final String LINUX_GET_ALL_GROUPS_CMD = "getent group" ;
	static final String LINUX_GET_GROUP_CMD = "getent group %s" ;

	// mainly for testing purposes
	// there might be a better way
	static final String MAC_GET_ALL_USERS_CMD = "dscl . -readall /Users UniqueID PrimaryGroupID | " +
			"awk 'BEGIN { OFS = \":\"; ORS=\"\\n\"; i=0;}" +
			"/RecordName: / {name = $2;i = 0;}/PrimaryGroupID: / {gid = $2;}" +
			"/^ / {if (i == 0) { i++; name = $1;}}" +
			"/UniqueID: / {uid = $2;print name, \"*\", gid, uid;}'" ;
	static final String MAC_GET_ALL_GROUPS_CMD = "dscl . -list /Groups PrimaryGroupID | " +
			"awk -v OFS=\":\" '{print $1, \"*\", $2, \"\"}'" ;
	static final String MAC_GET_GROUP_CMD = "dscl . -read /Groups/%1$s | paste -d, -s - | sed -e 's/:/|/g' | " +
			"awk -v OFS=\":\" -v ORS=\"\\n\" -F, '{print \"%1$s\",\"*\",$6,$4}' | " +
			"sed -e 's/:[^:]*| /:/g' | sed -e 's/ /,/g'" ;

	static final String BACKEND_PASSWD = "passwd";

	private boolean enumerateGroupMembers = false;
	private boolean useNss = false;

	private long lastUpdateTime = 0; // Last time maps were updated
	private long timeout = 0;

	private UserGroupSyncConfig config = UserGroupSyncConfig.getInstance() ;
	private Map<String,List<String>> user2GroupListMap = new HashMap<String,List<String>>();
	private Map<String,List<String>>  	internalUser2GroupListMap = new HashMap<String,List<String>>();
	private Map<String,String>			groupId2groupNameMap = new HashMap<String,String>() ;
	private int 						minimumUserId  = 0 ;
	private int							minimumGroupId = 0 ;

	private long passwordFileModifiedAt = 0 ;
	private long groupFileModifiedAt = 0 ;

	public static void main(String[] args) throws Throwable {
		UnixUserGroupBuilder ugbuilder = new UnixUserGroupBuilder() ;
		ugbuilder.init();
		ugbuilder.print(); 
	}
	
	public UnixUserGroupBuilder() {
		minimumUserId = Integer.parseInt(config.getMinUserId()) ;
		minimumGroupId = Integer.parseInt(config.getMinGroupId()) ;

		LOG.debug("Minimum UserId: " + minimumUserId + ", minimum GroupId: " + minimumGroupId) ;

		timeout = config.getUpdateMillisMin() ;
		enumerateGroupMembers = config.isGroupEnumerateEnabled();

		if (!config.getUnixBackend().equalsIgnoreCase(BACKEND_PASSWD)) {
			useNss = true;
		} else {
			LOG.warn("DEPRECATED: Unix backend is configured to use /etc/passwd and /etc/group files directly " +
					"instead of standard system mechanisms.");
		}
	}

	@Override
	public void init() throws Throwable {
		buildUserGroupInfo() ;
	}

	@Override
	public boolean isChanged() {
		if (useNss)
			return System.currentTimeMillis() - lastUpdateTime > timeout ;

		long TempPasswordFileModifiedAt = new File(UNIX_USER_PASSWORD_FILE).lastModified() ;
		if (passwordFileModifiedAt != TempPasswordFileModifiedAt) {
			return true ;
		}

		long TempGroupFileModifiedAt = new File(UNIX_GROUP_FILE).lastModified() ;
		if (groupFileModifiedAt != TempGroupFileModifiedAt) {
			return true ;
		}

		return false ;
	}


	@Override
	public void updateSink(UserGroupSink sink) throws Throwable {
		buildUserGroupInfo() ;

		for (Map.Entry<String, List<String>> entry : user2GroupListMap.entrySet()) {
		    String       user   = entry.getKey();
		    List<String> groups = entry.getValue();
		    
			try{
				sink.addOrUpdateUser(user, groups);
			}catch (Throwable t) {
				LOG.error("sink.addOrUpdateUser failed with exception: " + t.getMessage()
				+ ", for user: " + user
				+ ", groups: " + groups);
			}
		}
	}
	
	
	private void buildUserGroupInfo() throws Throwable {
		user2GroupListMap = new HashMap<String,List<String>>();
		groupId2groupNameMap = new HashMap<String, String>();

		if (OS.startsWith("Mac")) {
			buildUnixGroupList(MAC_GET_ALL_GROUPS_CMD, MAC_GET_GROUP_CMD, false);
			buildUnixUserList(MAC_GET_ALL_USERS_CMD);
		} else {
			if (!OS.startsWith("Linux")) {
				LOG.warn("Platform not recognized assuming Linux compatible");
			}
			buildUnixGroupList(LINUX_GET_ALL_GROUPS_CMD, LINUX_GET_GROUP_CMD, true);
			buildUnixUserList(LINUX_GET_ALL_USERS_CMD);
		}

		lastUpdateTime = System.currentTimeMillis() ;

		if (LOG.isDebugEnabled()) {
			print() ;
		}
	}
	
	private void print() {
		for(String user : user2GroupListMap.keySet()) {
			LOG.debug("USER:" + user) ;
			List<String> groups = user2GroupListMap.get(user) ;
			if (groups != null) {
				for(String group : groups) {
					LOG.debug("\tGROUP: " + group) ;
				}
			}
		}
	}
	
	private void buildUnixUserList(String command) throws Throwable {
		BufferedReader reader = null;
		Map<String, String> userName2uid = new HashMap<String, String>();

		try {
			if (!useNss) {
				File file = new File(UNIX_USER_PASSWORD_FILE);
				passwordFileModifiedAt = file.lastModified();
				FileInputStream fis = new FileInputStream(file);
				reader = new BufferedReader(new InputStreamReader(fis, StandardCharsets.UTF_8));
			} else {
				Process process = Runtime.getRuntime().exec(
						new String[]{"bash", "-c", command});

				reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
			}

			String line = null;

			while ((line = reader.readLine()) != null) {
				if (line.trim().isEmpty())
					continue;

				String[] tokens = line.split(":");

				int len = tokens.length;

				if (len < 3) {
					LOG.warn("Unable to parse: " + line);
					continue;
				}

				String userName = null ;
				String userId = null ;
				String groupId = null ;

				try {
					userName = tokens[0];
					userId = tokens[2];
					groupId = tokens[3];
				}
				catch(ArrayIndexOutOfBoundsException aiobe) {
					LOG.warn("Ignoring line - [" + line + "]: Unable to parse line for getting user information", aiobe) ;
					continue ;
				}

				int numUserId = -1;

				try {
					numUserId = Integer.parseInt(userId);
				} catch (NumberFormatException nfe) {
					LOG.warn("Unix UserId: [" + userId + "]: can not be parsed as valid int. considering as  -1.", nfe);
					numUserId = -1;
				}

				if (numUserId >= minimumUserId) {
					userName2uid.put(userName, userId);
					String groupName = groupId2groupNameMap.get(groupId);
					if (groupName != null) {
						List<String> groupList = new ArrayList<String>();
						groupList.add(groupName);
						// do we already know about this use's membership to other groups?  If so add those, too
						if (internalUser2GroupListMap.containsKey(userName)) {
							List<String> map = internalUser2GroupListMap.get(userName);

							// there could be duplicates
							map.remove(groupName);
							groupList.addAll(map);
						}
						user2GroupListMap.put(userName, groupList);
					} else {
						// we are ignoring the possibility that this user was present in /etc/groups.
						LOG.warn("Group Name could not be found for group id: [" + groupId + "]. Skipping adding user [" + userName + "] with id [" + userId + "].");
					}
				} else {
					LOG.debug("Skipping user [" + userName + "] since its userid [" + userId + "] is less than minuserid limit [" + minimumUserId + "].");
				}
			}
		} finally {
			if (reader != null)
				reader.close();
		}

		if (!useNss)
			return;

		// this does a reverse check as not all users might be listed in getent passwd
		if (enumerateGroupMembers) {
			String line = null;

			LOG.debug("Start drill down group members");
			for (Map.Entry<String, List<String>> entry : internalUser2GroupListMap.entrySet()) {
				// skip users we already now about
				if (user2GroupListMap.containsKey(entry.getKey()))
					continue;

				LOG.debug("Enumerating user " + entry.getKey());

				int numUserId = -1;
				try {
					numUserId = Integer.parseInt(userName2uid.get(entry.getKey()));
				} catch (NumberFormatException nfe) {
					numUserId = -1;
				}

				// if a user comes from an external group we might not have a uid
				if (numUserId < minimumUserId && numUserId != -1)
					continue;


				// "id" is same across Linux / BSD / MacOSX
				// gids are used as id might return groups with spaces, ie "domain users"
				Process process = Runtime.getRuntime().exec(
						new String[]{"bash", "-c", "id -G " + entry.getKey()});

				try {
					reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
					line = reader.readLine();
				} finally {
					reader.close();
				}

				LOG.debug("id -G returned " + line);

				if (line == null || line.trim().isEmpty()) {
					LOG.warn("User " + entry.getKey() + " could not be resolved");
					continue;
				}

				String[] gids = line.split(" ");

				// check if all groups returned by id are visible to ranger
				ArrayList<String> allowedGroups = new ArrayList<String>();
				for (String gid : gids) {
					int numGroupId = Integer.parseInt(gid);
					if (numGroupId < minimumGroupId)
						continue;

					String groupName = groupId2groupNameMap.get(gid);
					if (groupName != null)
						allowedGroups.add(groupName);
				}

				user2GroupListMap.put(entry.getKey(), allowedGroups);
			}
			LOG.debug("End drill down group members");
		}
	}

	private void parseMembers(String line) {
		if (line == null || line.isEmpty())
			return;

		String[] tokens = line.split(":");

		if (tokens.length < 2)
			return;

		String groupName = tokens[0];
		String groupId = tokens[2];
		String groupMembers = null;

		if (tokens.length > 3)
			groupMembers = tokens[3];

		if (groupId2groupNameMap.containsKey(groupId)) {
			groupId2groupNameMap.remove(groupId);
		}

		int numGroupId = Integer.parseInt(groupId);
		if (numGroupId < minimumGroupId)
			return;

		groupId2groupNameMap.put(groupId, groupName);

		if (groupMembers != null && !groupMembers.trim().isEmpty()) {
			for (String user : groupMembers.split(",")) {
				List<String> groupList = internalUser2GroupListMap.get(user);
				if (groupList == null) {
					groupList = new ArrayList<String>();
					internalUser2GroupListMap.put(user, groupList);
				}
				if (!groupList.contains(groupName)) {
					groupList.add(groupName);
				}
			}
		}
	}

	private void buildUnixGroupList(String allGroupsCmd, String groupCmd, boolean useGid) throws Throwable {
		LOG.debug("Start enumerating groups");
		BufferedReader reader = null;

		try {
			if (!useNss) {
				File file = new File(UNIX_GROUP_FILE);
				groupFileModifiedAt = file.lastModified();
				FileInputStream fis = new FileInputStream(file);
				reader = new BufferedReader(new InputStreamReader(fis, StandardCharsets.UTF_8));
			} else {
				Process process = Runtime.getRuntime().exec(
						new String[]{"bash", "-c", allGroupsCmd});
				reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
			}

			String line = null;

			while ((line = reader.readLine()) != null) {
				if (line.trim().isEmpty())
					continue;

				parseMembers(line);
			}
		} finally {
			if (reader != null)
				reader.close();
		}

		LOG.debug("End enumerating group");

		if (!useNss)
			return;

		if (enumerateGroupMembers) {
			LOG.debug("Start enumerating group members");
			String line = null;
			Map<String,String> copy = new HashMap<String, String>(groupId2groupNameMap);

			for (Map.Entry<String, String> group : copy.entrySet()) {
				LOG.debug("Enumerating group: " + group.getValue() + " GID(" + group.getKey() + ")");

				String command;
				if (useGid) {
					command = String.format(groupCmd, group.getKey());
				} else {
					command = String.format(groupCmd, group.getValue());
				}

				String[] cmd = new String[]{"bash", "-c", command + " " + group.getKey()};
				LOG.debug("Executing: " + Arrays.toString(cmd));

				try {
					Process process = Runtime.getRuntime().exec(cmd);
					reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
					line = reader.readLine();
				} finally {
					if (reader != null)
						reader.close();
				}
				LOG.debug("bash -c " + command + " for group " + group + " returned " + line);

				parseMembers(line);
			}
			LOG.debug("End enumerating group members");
		}

		if (config.getEnumerateGroups() != null) {
			String line = null;
			String[] groups = config.getEnumerateGroups().split(",");

			LOG.debug("Adding extra groups");

			for (String group : groups) {
				String command = String.format(groupCmd, group);
				String[] cmd = new String[]{"bash", "-c", command + " '" + group + "'"};
				LOG.debug("Executing: " + Arrays.toString(cmd));

				try {
					Process process = Runtime.getRuntime().exec(cmd);
					reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
					line = reader.readLine();
				} finally {
					if (reader != null)
						reader.close();
				}

				LOG.debug("bash -c " + command + " for group " + group + " returned " + line);

				parseMembers(line);
			}
			LOG.debug("Done adding extra groups");
		}
	}

	@VisibleForTesting
	Map<String,List<String>> getUser2GroupListMap() {
		return user2GroupListMap;
	}

	@VisibleForTesting
	Map<String,String> getGroupId2groupNameMap() {
		return groupId2groupNameMap;
	}

}

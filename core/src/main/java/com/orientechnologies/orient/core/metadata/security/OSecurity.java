/*
 * Copyright 1999-2010 Luca Garulli (l.garulli--at--orientechnologies.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.orientechnologies.orient.core.metadata.security;

import java.util.List;

import com.orientechnologies.orient.core.record.impl.ODocument;

/**
 * Manages users and roles.
 * 
 * @author Luca Garulli
 * 
 */
public interface OSecurity {
	public OUser create();

	public void load();

	public OUser authenticate(String iUsername, String iUserPassword);

	public OUser getUser(String iUserName);

	public OUser createUser(String iUserName, String iUserPassword, String[] iRoles);

	public boolean dropUser(String iUserName);

	public ORole getRole(String iRoleName);

	public ORole createRole(String iRoleName, ORole.ALLOW_MODES iAllowMode);

	public ORole createRole(String iRoleName, ORole iParent, ORole.ALLOW_MODES iAllowMode);

	public boolean dropRole(String iRoleName);

	public List<ODocument> getUsers();

	public List<ODocument> getRoles();

	public OUser repair();

	public void close();
}

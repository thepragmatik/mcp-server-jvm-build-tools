/*
 *
 *  Copyright 2025 Rahul Thakur
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.pragmatik.buildtools.cicd;

/**
 * Workflow-level permissions.
 *
 * @param contents       Contents permission: "read", "write", "none"
 * @param issues         Issues permission
 * @param pullRequests   Pull requests permission
 * @param packages       Packages permission
 * @param idToken        ID token permission for OIDC
 */
public record CiCdPermissions(String contents, String issues, String pullRequests, String packages, String idToken) {

    public static CiCdPermissions readAll() {
        return new CiCdPermissions("read", "read", "read", "read", null);
    }

    public static CiCdPermissions writeAll() {
        return new CiCdPermissions("write", "write", "write", "write", null);
    }
}

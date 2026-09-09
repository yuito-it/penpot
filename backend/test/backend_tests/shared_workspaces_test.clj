;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.shared-workspaces-test
  (:require
   [app.binfile.common :as bfc]
   [app.config :as cf]
   [app.db :as db]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.profile :as profile]
   [app.rpc.commands.projects :as projects]
   [app.rpc.commands.teams :as teams]
   [backend-tests.helpers :as th]
   [clojure.test :as t]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(t/deftest shared-only-provisioning-does-not-create-personal-workspaces
  (let [owner (th/create-profile* 1 {:is-active true})
        team (th/create-team* 1 {:profile-id (:id owner)})]
    (with-redefs [cf/flags (conj cf/flags :shared-workspaces-only)
                  cf/config (assoc cf/config :default-team-id (:id team))]
      (let [user (th/create-profile* 2 {:is-active true :backend "oidc"})
            memberships (db/query th/*pool* :team-profile-rel {:profile-id (:id user)})
            rel (first memberships)]
        (t/is (= (:id team) (:default-team-id user)))
        (t/is (= (:default-project-id team) (:default-project-id user)))
        (t/is (= 1 (count memberships)))
        (t/is (:can-edit rel))
        (t/is (false? (:is-owner rel)))
        (t/is (false? (:is-admin rel)))
        (t/is (not (:is-default (first (teams/get-teams th/*pool* (:id user))))))))))

(t/deftest personal-data-is-inaccessible-until-the-flag-is-disabled
  (let [user (th/create-profile* 1 {:is-active true})
        file (th/create-file* 1 {:profile-id (:id user) :project-id (:default-project-id user)})
        checks [#(teams/get-permissions th/*pool* (:id user) (:default-team-id user))
                #(#'projects/get-permissions th/*pool* (:id user) (:default-project-id user))
                #(bfc/get-file-permissions th/*pool* (:id user) (:id file))]]
    (with-redefs [cf/flags (conj cf/flags :shared-workspaces-only)]
      (doseq [check checks]
        (t/is (thrown-with-msg? clojure.lang.ExceptionInfo #".*" (check))))
      (t/is (empty? (teams/get-teams th/*pool* (:id user)))))
    (doseq [check checks] (t/is (map? (check))))
    (t/is (= 1 (count (teams/get-teams th/*pool* (:id user)))))))

(t/deftest existing-profiles-use-a-shared-fallback-without-changing-stored-defaults
  (let [user (th/create-profile* 1 {:is-active true})
        team (th/create-team* 1 {:profile-id (:id user)})]
    (with-redefs [cf/flags (conj cf/flags :shared-workspaces-only)]
      (t/is (= (:id team) (:default-team-id (profile/get-profile th/*pool* (:id user)))))
      (t/is (= (:default-project-id team)
               (:default-project-id (profile/get-profile-by-email th/*pool* (:email user))))))
    (t/is (= (:default-team-id user) (:default-team-id (profile/get-profile th/*pool* (:id user)))))))

(t/deftest only-instance-admins-can-create-shared-teams-in-restricted-mode
  (let [user (th/create-profile* 1 {:is-active true})
        params {::th/type :create-team ::rpc/profile-id (:id user) :name "Shared"}]
    (with-redefs [cf/flags (conj cf/flags :shared-workspaces-only)]
      (t/is (th/ex-of-code? (:error (th/command! params)) :shared-workspaces-only))
      (with-redefs [cf/config (assoc cf/config :admins #{(:email user)})]
        (t/is (nil? (:error (th/command! params))))))))

(t/deftest personal-rpc-reads-and-project-discovery-are-blocked
  (let [user (th/create-profile* 1 {:is-active true})
        team (th/create-team* 1 {:profile-id (:id user)})
        file (th/create-file* 1 {:profile-id (:id user) :project-id (:default-project-id user)})]
    (with-redefs [cf/flags (conj cf/flags :shared-workspaces-only)]
      (doseq [[command id] [[:get-team (:default-team-id user)]
                            [:get-project (:default-project-id user)]
                            [:get-file (:id file)]]]
        (t/is (th/ex-of-code? (:error (th/command! {::th/type command ::rpc/profile-id (:id user) :id id}))
                              :object-not-found)))
      (t/is (nil? (:error (th/command! {::th/type :get-team ::rpc/profile-id (:id user) :id (:id team)}))))
      (t/is (= [(:default-project-id team)]
               (mapv :id (:result (th/command! {::th/type :get-all-projects ::rpc/profile-id (:id user)}))))))))

(t/deftest preexisting-share-links-cannot-expose-personal-files
  (let [owner (th/create-profile* 1 {:is-active true})
        viewer (th/create-profile* 2 {:is-active true})
        file (th/create-file* 1 {:profile-id (:id owner) :project-id (:default-project-id owner)})
        page-id (get-in file [:data :pages 0])
        share (:result (th/command! {::th/type :create-share-link ::rpc/profile-id (:id owner)
                                     :file-id (:id file) :pages #{page-id}
                                     :who-comment "team" :who-inspect "all"}))
        request {::th/type :get-page ::rpc/profile-id (:id viewer)
                 :file-id (:id file) :page-id page-id :share-id (:id share)}]
    (t/is (some? (:id share)))
    (t/is (nil? (:error (th/command! request))))
    (with-redefs [cf/flags (conj cf/flags :shared-workspaces-only)]
      (t/is (th/ex-of-code? (:error (th/command! request)) :object-not-found)))
    (t/is (nil? (:error (th/command! request))))))

(t/deftest invalid-default-team-prevents-provisioning
  (let [owner (th/create-profile* 1 {:is-active true})]
    (doseq [team-id [nil (:default-team-id owner)]]
      (with-redefs [cf/flags (conj cf/flags :shared-workspaces-only)
                    cf/config (assoc cf/config :default-team-id team-id)]
        (try
          (th/create-profile* (if team-id 2 3) {:is-active true})
          (t/is false "Expected invalid shared default team to reject provisioning")
          (catch clojure.lang.ExceptionInfo cause
            (t/is (th/ex-of-code? cause :default-shared-team-required))))))))

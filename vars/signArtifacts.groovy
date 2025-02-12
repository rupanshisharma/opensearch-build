/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/*
SignArtifacts signs the given artifacts and saves the signature in the same directory
@param Map[artifactPath] <Required> - Path to yml or artifact file.
@param Map[component] <Optional> - Path to yml or artifact file.
@param Map[type] <Optional> - Artifact type in the manifest, [type] is required for signing yml.
@param Map[sigtype] <Optional> - The signature type of signing artifacts. e.g. '.sig'. Required for non-yml artifacts signing.
@param Map[platform] <Required> - The distribution platform for signing.
*/
void call(Map args = [:]) {

    if (args.sigtype.equals('.rpm')) {
        echo "RPM Add Sign"

        withAWS(role: "${SIGN_ASM_ROLE}", roleAccount: "${SIGN_ASM_ACCOUNT}", duration: 900, roleSessionName: 'jenkins-signing-session') {
            withCredentials([
                string(credentialsId: 'jenkins-rpm-signing-asm-pass-id', variable: 'SIGNING_PASS_ID'),
                string(credentialsId: 'jenkins-rpm-signing-asm-secret-id', variable: 'SIGNING_SECRET_ID')])
                {
                    sh """
                        set -e
                        set +x

                        ARTIFACT_PATH="${args.artifactPath}"

                        echo "------------------------------------------------------------------------"
                        echo "Check Utility Versions"
                        gpg_version_requirement="2.2.0"
                        rpm_version_requirement="4.13.0" # https://bugzilla.redhat.com/show_bug.cgi?id=227632

                        gpg_version_check=`gpg --version | head -n 1 | grep -oE '[0-9.]+'`
                        gpg_version_check_final=`echo \$gpg_version_check \$gpg_version_requirement | tr ' ' '\n' | sort -V | head -n 1`
                        rpm_version_check=`rpm --version | head -n 1 | grep -oE '[0-9.]+'`
                        rpm_version_check_final=`echo \$rpm_version_check \$rpm_version_requirement | tr ' ' '\n' | sort -V | head -n 1`

                        echo -e "gpg_version_requirement gpg_version_check"
                        echo -e "\$gpg_version_requirement \$gpg_version_check"
                        echo -e "rpm_version_requirement rpm_version_check"
                        echo -e "\$rpm_version_requirement \$rpm_version_check"

                        if [[ \$gpg_version_requirement = \$gpg_version_check_final ]] && [[ \$rpm_version_requirement = \$rpm_version_check_final ]]; then
                            echo "Utility version is equal or greater than set limit, continue."
                        else
                            echo "Utility version is lower than set limit, exit 1"
                            exit 1
                        fi

                        export GPG_TTY=`tty`

                        echo "------------------------------------------------------------------------"
                        echo "Setup RPM Macros"
                        cp -v scripts/pkg/sign_templates/rpmmacros ~/.rpmmacros
                        sed -i "s/##key_name##/OpenSearch project/g;s/##passphrase_name##/passphrase/g" ~/.rpmmacros

                        echo "------------------------------------------------------------------------"
                        echo "Import OpenSearch keys"
                        aws secretsmanager get-secret-value --region "${SIGN_ASM_REGION}" --secret-id "${SIGNING_PASS_ID}" | jq -r .SecretBinary | base64 --decode > passphrase
                        aws secretsmanager get-secret-value --region "${SIGN_ASM_REGION}" --secret-id "${SIGNING_SECRET_ID}" | jq -r .SecretBinary | base64 --decode | gpg --quiet --import --pinentry-mode loopback --passphrase-file passphrase -

                        echo "------------------------------------------------------------------------"
                        echo "Start Signing Rpm"

                        if file \$ARTIFACT_PATH | grep -q directory; then

                            echo "Sign directory"
                            for rpm_file in `ls \$ARTIFACT_PATH`; do
                                if file \$ARTIFACT_PATH/\$rpm_file | grep -q RPM; then
                                    rpm --addsign \$ARTIFACT_PATH/\$rpm_file
                                    rpm -qip \$ARTIFACT_PATH/\$rpm_file | grep Signature
                                fi
                            done

                        elif file \$ARTIFACT_PATH | grep -q RPM; then
                            echo "Sign single rpm"
                            rpm --addsign \$ARTIFACT_PATH
                            rpm -qip \$ARTIFACT_PATH | grep Signature

                        else
                            echo "This is neither a directory nor a RPM pkg, exit 1"
                            exit 1
                        fi

                        echo "------------------------------------------------------------------------"
                        echo "Clean up gpg"
                        gpg --batch --yes --delete-secret-keys $SIGN_ASM_KEYID
                        gpg --batch --yes --delete-keys $SIGN_ASM_KEYID
                        rm -v passphrase

                    """

                }
        }

    }
    else {
        echo "PGP Signature Signing"

        if( !fileExists("$WORKSPACE/sign.sh")) {
            git url: 'https://github.com/opensearch-project/opensearch-build.git', branch: 'main'
        }

        importPGPKey()
        
        String arguments = generateArguments(args)

        // Sign artifacts
        withCredentials([usernamePassword(credentialsId: "${GITHUB_BOT_TOKEN_NAME}", usernameVariable: 'GITHUB_USER', passwordVariable: 'GITHUB_TOKEN')]) {
            sh """
                #!/bin/bash
                set +x
                export ROLE=${SIGNER_CLIENT_ROLE}
                export EXTERNAL_ID=${SIGNER_CLIENT_EXTERNAL_ID}
                export UNSIGNED_BUCKET=${SIGNER_CLIENT_UNSIGNED_BUCKET}
                export SIGNED_BUCKET=${SIGNER_CLIENT_SIGNED_BUCKET}

                $WORKSPACE/sign.sh ${arguments}
            """
        }

    }
}

String generateArguments(args) {
    String artifactPath = args.remove("artifactPath")
    // artifactPath is mandatory and the first argument
    String arguments = artifactPath
    // generation command line arguments
    args.each{key, value -> arguments += " --${key}=${value}"}
    return arguments
}

void importPGPKey(){

    sh "curl -sSL https://artifacts.opensearch.org/publickeys/opensearch.pgp | gpg --import -"

}


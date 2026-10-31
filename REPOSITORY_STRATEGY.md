# Repository strategy

Use one monorepo. Shared immutable models, protocol fixtures, scanner code and resolver behavior need atomic review; separate repositories would add library publishing, compatibility coordination and CI maintenance without improving host independence.

Every application has its own Gradle packaging target. The server and agent have separate Dockerfiles/Compose stacks. Clients ship independently. Releases record protocol compatibility rather than requiring synchronized upgrades. Container images have separate GHCR names under the eventual approved repository owner.

A hybrid infrastructure repository can be considered if operators later need independent access controls. The user created https://github.com/athegreat90/minecraft-modsync and authorized publishing the source. Local origin is configured; API import history differs from the original local history because terminal Git transport is unavailable.

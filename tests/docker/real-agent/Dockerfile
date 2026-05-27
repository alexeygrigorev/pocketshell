FROM node:24-bookworm-slim

ARG OPENCODE_VERSION=1.15.1
ARG CODEX_VERSION=0.133.0
ARG CLAUDE_CODE_VERSION=2.1.150

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        bash \
        ca-certificates \
        git \
        less \
        make \
        g++ \
        openssh-client \
        openssh-server \
        procps \
        python3 \
        ripgrep \
        tmux \
    && rm -rf /var/lib/apt/lists/*

RUN npm install -g \
        "opencode-ai@${OPENCODE_VERSION}" \
        "@openai/codex@${CODEX_VERSION}" \
        "@anthropic-ai/claude-code@${CLAUDE_CODE_VERSION}" \
    && npm cache clean --force

RUN ssh-keygen -A \
    && useradd --create-home --shell /bin/bash testuser \
    && mkdir -p /run/sshd /home/testuser/.ssh /workspace/pocketshell \
    && chmod 700 /home/testuser/.ssh \
    && chown -R testuser:testuser /home/testuser /workspace

COPY test_key.pub /home/testuser/.ssh/authorized_keys
# Issue #150: see Dockerfile.ssh — same rationale for the in-container
# compose healthcheck. The private key is a committed test fixture.
COPY test_key /root/test_key
COPY real-agent/entrypoint.sh /usr/local/bin/pocketshell-real-agent-entrypoint
COPY real-agent/start-agent-sessions.sh /usr/local/bin/pocketshell-start-agent-sessions

RUN chmod 600 /home/testuser/.ssh/authorized_keys /root/test_key \
    && chmod +x \
        /usr/local/bin/pocketshell-real-agent-entrypoint \
        /usr/local/bin/pocketshell-start-agent-sessions \
    && chown -R testuser:testuser /home/testuser/.ssh

EXPOSE 22

CMD ["/usr/local/bin/pocketshell-real-agent-entrypoint"]

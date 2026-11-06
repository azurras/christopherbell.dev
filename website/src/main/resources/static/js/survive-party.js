import { appendSurviveLogMessage } from './survive-log.js';

const COMMANDS = {
  INCOMING: ['ACCEPT', 'DECLINE'],
  OUTGOING: ['CANCEL'],
  MEMBER: ['LEAVE'],
};

/** Renders private party state and keeps selection and focus stable across polls. */
export function mountSurviveParty(documentRoot, sendCommand) {
  const form = documentRoot.getElementById('survivePartyForm');
  if (!form) return null;
  const recipient = documentRoot.getElementById('survivePartyRecipient');
  const controls = documentRoot.getElementById('survivePartyControls');
  const status = documentRoot.getElementById('survivePartyStatus');
  const inviteButton = form.querySelector('button');
  let party = null;
  let recipientSignature = '';

  function invite(event) {
    event.preventDefault();
    if (!inviteButton.disabled && recipient.value) sendCommand('INVITE', recipient.value);
  }

  function respond(event) {
    const button = event.target.closest('[data-party-command]');
    if (party && button && !button.hidden && !button.disabled) sendCommand(button.dataset.partyCommand, party.id);
  }

  form.addEventListener('submit', invite);
  controls.addEventListener('click', respond);
  return {
    render(state, mutationPending) {
      const nextParty = state?.party ?? null;
      const recipients = state?.recipients ?? [];
      const signature = JSON.stringify(recipients);
      if (signature !== recipientSignature) {
        const selectedId = recipient.value;
        recipient.replaceChildren(...recipients.map(({ survivorId, name }) => {
          const option = documentRoot.createElement('option');
          option.value = survivorId;
          option.textContent = name;
          return option;
        }));
        if (recipients.some(({ survivorId }) => survivorId === selectedId)) recipient.value = selectedId;
        recipientSignature = signature;
      }
      if (state && (party?.id !== nextParty?.id || party?.state !== nextParty?.state)) {
        const message = nextParty ? {
          INCOMING: `${nextParty.name} invited you to a party. Open Main Menu → Party.`,
          OUTGOING: `Party invitation sent to ${nextParty.name}.`,
          MEMBER: `You are in a party with ${nextParty.name}.`,
        }[nextParty.state] : party?.state === 'MEMBER' ? 'Your party ended.' : 'The party invitation closed.';
        if (message) appendSurviveLogMessage(documentRoot, message);
      }
      party = nextParty;
      form.hidden = Boolean(party);
      inviteButton.disabled = mutationPending || recipients.length === 0;
      controls.hidden = !party;
      status.textContent = party ? {
        INCOMING: `Invitation from ${party.name}`,
        OUTGOING: `Waiting for ${party.name}`,
        MEMBER: `Party member: ${party.name}`,
      }[party.state] : '';
      for (const button of controls.querySelectorAll('[data-party-command]')) {
        button.hidden = !COMMANDS[party?.state]?.includes(button.dataset.partyCommand);
        button.disabled = mutationPending;
      }
    },
    dispose() {
      form.removeEventListener('submit', invite);
      controls.removeEventListener('click', respond);
    },
  };
}

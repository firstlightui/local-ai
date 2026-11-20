/**
 * Firstlight Local AI for NativePHP Mobile web views (Inertia + Vue or React).
 *
 * Map the import in your app's package.json:
 *
 *     "imports": { "#local-ai": "./vendor/firstlightui/local-ai/resources/js/localAI.js" }
 *
 * @example
 * import { Availability, Generate, Status, Events } from '#local-ai';
 * import { On } from '#nativephp';
 *
 * On(Events.TextDeltaGenerated, ({ id, delta }) => { ... });
 * if ((await Availability()).status === 'available') {
 *     const { id } = await Generate({ messages: [{ role: 'user', content: 'Hi' }] });
 * }
 */

const baseUrl = '/_native/api/call';

const OPTIONS = ['temperature', 'maxTokens'];
const ROLES = ['system', 'user', 'assistant'];

/**
 * A native failure. `code` is one of the codes in the README, such as
 * `unavailable`, `busy` or `unknown_operation`.
 */
export class LocalAIError extends Error {
    constructor(code, message) {
        super(message);
        this.name = 'LocalAIError';
        this.code = code;
    }
}

async function bridgeCall(method, params = {}) {
    const response = await fetch(baseUrl, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            'X-CSRF-TOKEN': document.querySelector('meta[name="csrf-token"]')?.content || '',
        },
        body: JSON.stringify({ method, params }),
    });

    const result = await response.json();

    if (result.status === 'error') {
        throw new LocalAIError(String(result.code || 'bridge_failed').toLowerCase(), result.message || `${method} failed.`);
    }

    return result.data;
}

/**
 * Whether the operating system's on-device model can be used.
 * @returns {Promise<{status: 'available'|'downloadable'|'downloading'|'unavailable', reason: string, platform: 'ios'|'android'}>}
 */
export async function Availability() {
    return bridgeCall('LocalAI.Availability');
}

/**
 * Ask the OS to fetch its model when Availability() reports `downloadable`.
 * Android only; progress arrives as ModelDownloadProgress events. iOS reports
 * the current availability.
 */
export async function Download() {
    return bridgeCall('LocalAI.Download');
}

/**
 * Queue a streamed generation and return at once. System messages may only
 * come first, and the last message must come from the user.
 *
 * @param {{messages: Array<{role: 'system'|'user'|'assistant', content: string}>, temperature?: number, maxTokens?: number, id?: string}} request
 * @returns {Promise<{id: string, status: 'queued'}>}
 */
export async function Generate({ messages, id = crypto.randomUUID(), ...options } = {}) {
    return bridgeCall('LocalAI.Generate', { id, ...validateMessages(messages), ...validateOptions(options) });
}

/**
 * Request cancellation. The terminal state arrives as GenerationCancelled, or
 * as GenerationCompleted if completion won the race.
 * @param {string} id
 */
export async function Cancel(id) {
    return bridgeCall('LocalAI.Cancel', { id });
}

/**
 * The authoritative snapshot of a generation. Reconcile with it after
 * navigation or missed events.
 * @param {string} id
 * @returns {Promise<{id: string, status: 'queued'|'running'|'completed'|'failed'|'cancelled', text: string, sequence: number, errorCode: string, errorMessage: string}>}
 */
export async function Status(id) {
    return bridgeCall('LocalAI.Status', { id });
}

/** Event names to pass to `On` and `Off` from '#nativephp'. */
export const Events = {
    TextDeltaGenerated: 'FirstlightUI\\LocalAI\\Events\\TextDeltaGenerated',
    GenerationCompleted: 'FirstlightUI\\LocalAI\\Events\\GenerationCompleted',
    GenerationFailed: 'FirstlightUI\\LocalAI\\Events\\GenerationFailed',
    GenerationCancelled: 'FirstlightUI\\LocalAI\\Events\\GenerationCancelled',
    ModelDownloadProgress: 'FirstlightUI\\LocalAI\\Events\\ModelDownloadProgress',
};

export const LocalAI = { Availability, Download, Generate, Cancel, Status, Events };

export default LocalAI;

function validateMessages(messages) {
    if (!Array.isArray(messages) || messages.length === 0) {
        throw new TypeError('Messages must be a non-empty list.');
    }

    const instructions = [];
    const conversation = [];

    messages.forEach((message, index) => {
        const { role, content } = message ?? {};

        if (!ROLES.includes(role)) {
            throw new TypeError(`Message ${index} has an unsupported role.`);
        }

        if (typeof content !== 'string' || content.trim() === '') {
            throw new TypeError(`Message ${index} must have non-empty string content.`);
        }

        if (role === 'system') {
            if (conversation.length > 0) {
                throw new TypeError('System messages must come before the conversation.');
            }
            instructions.push(content);
            return;
        }

        conversation.push({ role, content });
    });

    if (conversation.length === 0 || conversation[conversation.length - 1].role !== 'user') {
        throw new TypeError('The last message must come from the user.');
    }

    return { instructions: instructions.join('\n\n'), messages: conversation };
}

function validateOptions(options) {
    const unknown = Object.keys(options).filter((key) => !OPTIONS.includes(key));

    if (unknown.length > 0) {
        throw new TypeError(`Unsupported options: ${unknown.join(', ')}.`);
    }

    if ('temperature' in options) {
        const { temperature } = options;
        if (typeof temperature !== 'number' || !Number.isFinite(temperature) || temperature < 0 || temperature > 2) {
            throw new TypeError('temperature must be a number from 0 to 2.');
        }
    }

    if ('maxTokens' in options && (!Number.isInteger(options.maxTokens) || options.maxTokens < 1)) {
        throw new TypeError('maxTokens must be a positive integer.');
    }

    return options;
}

-- Contract v1.8 §11.2: reactions are indexed too, with kind = 'reaction' (null = a normal
-- message). Acks skip reactions; a reaction can't be a reaction target. Additive.
ALTER TABLE message_index ADD kind text;
